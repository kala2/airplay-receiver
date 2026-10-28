/* Host-side unit test for the dns_sd shim (TXT encoding, key replacement, listener, port order).
 * Run with scripts/test-native.sh. */
#include "dns_sd.h"
#include "dns_sd_shim.h"
#include <arpa/inet.h>
#include <stdio.h>
#include <string.h>
static int calls; static char got[512];
static void l(void *c, int reg, const char *n, const char *t, uint16_t port, const uint8_t *txt, uint16_t len) {
  (void)c; calls++; int o = snprintf(got, sizeof got, "%d|%s|%s|%u|", reg, n, t, port);
  for (uint16_t i = 0; i < len;) { uint8_t L = txt[i]; o += snprintf(got+o, sizeof got - o, "[%.*s]", L, (const char*)&txt[i+1]); i += 1+L; }
}
int main(void) {
  TXTRecordRef r; TXTRecordCreate(&r, 0, NULL);
  TXTRecordSetValue(&r, "ch", 1, "2");
  TXTRecordSetValue(&r, "sf", 4, "0x84");
  TXTRecordSetValue(&r, "pk", 3, "abc");
  TXTRecordSetValue(&r, "SF", 3, "0x4");   /* replaces, case-insensitive */
  TXTRecordSetValue(&r, "flag", 0, NULL);  /* key without value */
  dns_sd_shim_set_listener(l, NULL);
  DNSServiceRef s; DNSServiceRegister(&s, 0, 0, "AABB@TV", "_raop._tcp", NULL, NULL, htons(7000),
     TXTRecordGetLength(&r), TXTRecordGetBytesPtr(&r), NULL, NULL);
  const char *want = "1|AABB@TV|_raop._tcp|7000|[ch=2][pk=abc][SF=0x4][flag]";
  int ok1 = strcmp(got, want) == 0; printf("register: %s\n  %s\n", ok1 ? "OK" : "FAIL", got);
  DNSServiceRefDeallocate(s);
  int ok2 = calls == 2 && got[0] == '0'; printf("unregister: %s\n", ok2 ? "OK" : "FAIL");
  TXTRecordDeallocate(&r);
  return !(ok1 && ok2);
}
