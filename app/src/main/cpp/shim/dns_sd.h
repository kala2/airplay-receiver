/*
 * Minimal replacement for Apple's <dns_sd.h>, covering exactly what UxPlay's lib/dnssd.c uses
 * (verified against UxPlay v1.73.7): DNSServiceRegister, DNSServiceRefDeallocate and the
 * TXTRecord* helpers.
 *
 * Instead of talking to an mDNS daemon, DNSServiceRegister hands the service name, type, port and
 * the raw TXT record to a listener (see dns_sd_shim.h). On Android the listener forwards them to
 * Kotlin, which registers them with NsdManager. This keeps the TXT values exactly as UxPlay
 * generates them.
 *
 * Part of the AirPlayRx app (GPL-3.0).
 */
#ifndef AIRPLAYRX_DNS_SD_H
#define AIRPLAYRX_DNS_SD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DNSSD_API

typedef struct _DNSServiceRef_t *DNSServiceRef;
typedef uint32_t DNSServiceFlags;
typedef int32_t DNSServiceErrorType;

/* Same size and alignment as Apple's definition; dnssd.c embeds it by value in its struct. */
typedef union _TXTRecordRef_t {
    char PrivateData[16];
    char *ForceNaturalAlignment;
} TXTRecordRef;

enum {
    kDNSServiceErr_NoError = 0,
    kDNSServiceErr_Unknown = -65537,
    kDNSServiceErr_NoMemory = -65539,
    kDNSServiceErr_BadParam = -65540,
    kDNSServiceErr_Invalid = -65549,
};

typedef void (*DNSServiceRegisterReply)(DNSServiceRef sdRef, DNSServiceFlags flags,
                                        DNSServiceErrorType errorCode, const char *name,
                                        const char *regtype, const char *domain, void *context);

/* port is in network byte order, as in Bonjour. callBack is never invoked by this shim. */
DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags,
                                       uint32_t interfaceIndex, const char *name,
                                       const char *regtype, const char *domain, const char *host,
                                       uint16_t port, uint16_t txtLen, const void *txtRecord,
                                       DNSServiceRegisterReply callBack, void *context);
void DNSServiceRefDeallocate(DNSServiceRef sdRef);

/* buffer/bufferLen are ignored: the shim always manages its own growable buffer. */
void TXTRecordCreate(TXTRecordRef *txtRecord, uint16_t bufferLen, void *buffer);
void TXTRecordDeallocate(TXTRecordRef *txtRecord);
/* Replaces the value if the key already exists, as Bonjour does. */
DNSServiceErrorType TXTRecordSetValue(TXTRecordRef *txtRecord, const char *key,
                                      uint8_t valueSize, const void *value);
uint16_t TXTRecordGetLength(const TXTRecordRef *txtRecord);
const void *TXTRecordGetBytesPtr(const TXTRecordRef *txtRecord);

#ifdef __cplusplus
}
#endif
#endif
