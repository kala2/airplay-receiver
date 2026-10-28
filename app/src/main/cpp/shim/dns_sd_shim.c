/*
 * dns_sd shim implementation — see dns_sd.h for the why.
 * Part of the AirPlayRx app (GPL-3.0).
 */
#include "dns_sd.h"
#include "dns_sd_shim.h"

#include <arpa/inet.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* ---- TXT records ------------------------------------------------------------------------- */

/* Lives behind TXTRecordRef.PrivateData, which is too small to hold the bytes themselves. */
typedef struct {
    uint8_t *bytes;
    uint16_t len;
    uint16_t cap;
} txt_buf;

static txt_buf **txt_slot(const TXTRecordRef *ref) {
    return (txt_buf **) &((TXTRecordRef *) ref)->PrivateData;
}

void TXTRecordCreate(TXTRecordRef *txtRecord, uint16_t bufferLen, void *buffer) {
    (void) bufferLen;
    (void) buffer;
    memset(txtRecord, 0, sizeof(*txtRecord));
    *txt_slot(txtRecord) = calloc(1, sizeof(txt_buf));
}

void TXTRecordDeallocate(TXTRecordRef *txtRecord) {
    txt_buf *t = *txt_slot(txtRecord);
    if (t) {
        free(t->bytes);
        free(t);
        *txt_slot(txtRecord) = NULL;
    }
}

/* Returns the offset of the entry for key, or -1. Keys are matched case-insensitively. */
static int txt_find(const txt_buf *t, const char *key, size_t key_len) {
    uint16_t i = 0;
    while (i < t->len) {
        uint8_t entry_len = t->bytes[i];
        const char *entry = (const char *) &t->bytes[i + 1];
        if (entry_len >= key_len && strncasecmp(entry, key, key_len) == 0 &&
            (entry_len == key_len || entry[key_len] == '=')) {
            return i;
        }
        i += 1 + entry_len;
    }
    return -1;
}

DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *txtRecord, const char *key,
                                      uint8_t valueSize, const void *value) {
    txt_buf *t = *txt_slot(txtRecord);
    if (!t || !key) return kDNSServiceErr_BadParam;
    size_t key_len = strlen(key);
    size_t entry_len = key_len + (value ? 1 + valueSize : 0);
    if (key_len == 0 || entry_len > 255) return kDNSServiceErr_Invalid;

    int existing = txt_find(t, key, key_len);
    if (existing >= 0) {
        uint16_t old = 1 + t->bytes[existing];
        memmove(&t->bytes[existing], &t->bytes[existing + old], t->len - existing - old);
        t->len -= old;
    }

    size_t needed = (size_t) t->len + 1 + entry_len;
    if (needed > UINT16_MAX) return kDNSServiceErr_NoMemory;
    if (needed > t->cap) {
        uint16_t cap = (uint16_t) (needed < 256 ? 256 : (needed * 2 > UINT16_MAX ? UINT16_MAX : needed * 2));
        uint8_t *grown = realloc(t->bytes, cap);
        if (!grown) return kDNSServiceErr_NoMemory;
        t->bytes = grown;
        t->cap = cap;
    }

    uint8_t *p = &t->bytes[t->len];
    *p++ = (uint8_t) entry_len;
    memcpy(p, key, key_len);
    p += key_len;
    if (value) {
        *p++ = '=';
        memcpy(p, value, valueSize);
    }
    t->len = (uint16_t) needed;
    return kDNSServiceErr_NoError;
}

uint16_t TXTRecordGetLength(const TXTRecordRef *txtRecord) {
    const txt_buf *t = *txt_slot(txtRecord);
    return t ? t->len : 0;
}

const void *TXTRecordGetBytesPtr(const TXTRecordRef *txtRecord) {
    const txt_buf *t = *txt_slot(txtRecord);
    return t ? t->bytes : NULL;
}

/* ---- Service registration ---------------------------------------------------------------- */

struct _DNSServiceRef_t {
    char *name;
    char *regtype;
    uint16_t port; /* host byte order */
};

static pthread_mutex_t listener_lock = PTHREAD_MUTEX_INITIALIZER;
static dns_sd_shim_listener listener;
static void *listener_ctx;

void dns_sd_shim_set_listener(dns_sd_shim_listener l, void *ctx) {
    pthread_mutex_lock(&listener_lock);
    listener = l;
    listener_ctx = ctx;
    pthread_mutex_unlock(&listener_lock);
}

static void notify(int registered, const struct _DNSServiceRef_t *s, const uint8_t *txt,
                   uint16_t txt_len) {
    pthread_mutex_lock(&listener_lock);
    if (listener) listener(listener_ctx, registered, s->name, s->regtype, s->port, txt, txt_len);
    pthread_mutex_unlock(&listener_lock);
}

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags,
                                       uint32_t interfaceIndex, const char *name,
                                       const char *regtype, const char *domain, const char *host,
                                       uint16_t port, uint16_t txtLen, const void *txtRecord,
                                       DNSServiceRegisterReply callBack, void *context) {
    (void) flags;
    (void) interfaceIndex;
    (void) domain;
    (void) host;
    (void) callBack;
    (void) context;
    if (!sdRef || !name || !regtype) return kDNSServiceErr_BadParam;

    struct _DNSServiceRef_t *s = calloc(1, sizeof(*s));
    if (!s) return kDNSServiceErr_NoMemory;
    s->name = strdup(name);
    s->regtype = strdup(regtype);
    s->port = ntohs(port);
    if (!s->name || !s->regtype) {
        DNSServiceRefDeallocate(s);
        return kDNSServiceErr_NoMemory;
    }

    notify(1, s, (const uint8_t *) txtRecord, txtLen);
    *sdRef = s;
    return kDNSServiceErr_NoError;
}

void DNSServiceRefDeallocate(DNSServiceRef sdRef) {
    if (!sdRef) return;
    if (sdRef->name && sdRef->regtype) notify(0, sdRef, NULL, 0);
    free(sdRef->name);
    free(sdRef->regtype);
    free(sdRef);
}
