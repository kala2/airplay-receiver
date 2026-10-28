// Owns the UxPlay RAOP server and its dns_sd registration (one instance per process).
// Part of the AirPlayRx app (GPL-3.0).
#pragma once

#include <cstdint>

namespace airplayrx {

struct ServerConfig {
    const char *name;        // shown on the iPhone, e.g. "Living Room TV"
    const char *mac;         // "02:1a:2b:3c:4d:5e" — lowercase, as UxPlay's random_mac()
    int width = 1920;
    int height = 1080;
    int refresh_rate = 60;
    int max_fps = 60;
};

// Starts the server and registers _raop._tcp + _airplay._tcp through the dns_sd shim.
// Returns the TCP port (> 0) or a negative error. Idempotent: returns the running port if
// already started. Blocking; call from a worker thread.
int server_start(const ServerConfig &config);

// Unregisters the services and destroys the server. Idempotent. Blocking.
void server_stop();

}  // namespace airplayrx
