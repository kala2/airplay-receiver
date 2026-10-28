/* Host-side unit test for inspect_h264 / VideoStats. Run with scripts/test-native.sh. */
#include "video_stats.h"

#include <cstdio>
#include <vector>

using airplayrx::inspect_h264;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static std::vector<uint8_t> nal(uint8_t header, int payload) {
    std::vector<uint8_t> v = {0, 0, 0, 1, header};
    for (int i = 0; i < payload; i++) v.push_back(0xAB);
    return v;
}
static std::vector<uint8_t> cat(std::initializer_list<std::vector<uint8_t>> parts) {
    std::vector<uint8_t> out;
    for (auto &p : parts) out.insert(out.end(), p.begin(), p.end());
    return out;
}

int main() {
    // SPS(0x67) + PPS(0x68) + IDR(0x65): first frame of a session.
    auto key = cat({nal(0x67, 20), nal(0x68, 4), nal(0x65, 5000)});
    auto i = inspect_h264(key.data(), (int) key.size());
    CHECK(i.valid && i.has_sps && i.has_pps && i.is_idr);

    // Plain P-frame (0x41).
    auto p = nal(0x41, 3000);
    i = inspect_h264(p.data(), (int) p.size());
    CHECK(i.valid && !i.has_sps && !i.is_idr);

    // SEI(0x06) then IDR.
    auto sei = cat({nal(0x06, 10), nal(0x65, 100)});
    i = inspect_h264(sei.data(), (int) sei.size());
    CHECK(i.valid && i.is_idr);

    // UxPlay marks decrypt failures with data[0] = 1.
    auto bad = p;
    bad[0] = 1;
    CHECK(!inspect_h264(bad.data(), (int) bad.size()).valid);

    // Degenerate inputs must not crash.
    CHECK(!inspect_h264(nullptr, 0).valid);
    uint8_t tiny[3] = {0, 0, 0};
    CHECK(!inspect_h264(tiny, 3).valid);
    uint8_t only_start[4] = {0, 0, 0, 1};
    CHECK(inspect_h264(only_start, 4).valid);

    // Stats: 60 frames over 5 s → one summary line, then a session total.
    airplayrx::VideoStats stats;
    for (int f = 0; f <= 300; f++) stats.on_frame(f % 60 ? p.data() : key.data(),
                                                   (int) (f % 60 ? p.size() : key.size()),
                                                   1000000ull + (uint64_t) f * 16667,
                                                   /*lead_us=*/50000 - f * 10);
    stats.end_session("test");

    printf(failures ? "video_stats: %d FAILED\n" : "video_stats: OK\n", failures);
    return failures != 0;
}
