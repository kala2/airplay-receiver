/*
 * Hook for receiving service registrations made through the dns_sd shim.
 * Part of the AirPlayRx app (GPL-3.0).
 */
#ifndef AIRPLAYRX_DNS_SD_SHIM_H
#define AIRPLAYRX_DNS_SD_SHIM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Called synchronously on the thread that calls dnssd_register_* / dnssd_unregister_*.
 * registered = 1 on register, 0 on unregister. port is in host byte order.
 * txt is the raw DNS-SD TXT record (length-prefixed "key=value" entries), valid only during
 * the call.
 */
typedef void (*dns_sd_shim_listener)(void *ctx, int registered, const char *name,
                                     const char *regtype, uint16_t port, const uint8_t *txt,
                                     uint16_t txt_len);

void dns_sd_shim_set_listener(dns_sd_shim_listener listener, void *ctx);

#ifdef __cplusplus
}
#endif
#endif
