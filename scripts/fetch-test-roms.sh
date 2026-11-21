#!/usr/bin/env bash
# Downloads public-domain NES test ROMs used by the unit tests.
set -euo pipefail
cd "$(dirname "$0")/../app/src/test/resources"
mkdir -p roms && cd roms
B=https://raw.githubusercontent.com/christopherpow/nes-test-roms/master
for f in other/nestest.nes other/nestest.log \
  instr_test-v5/official_only.nes instr_test-v5/all_instrs.nes instr_timing/instr_timing.nes \
  mmc3_test_2/rom_singles/1-clocking.nes mmc3_test_2/rom_singles/2-details.nes \
  mmc3_test_2/rom_singles/3-A12_clocking.nes \
  ppu_vbl_nmi/rom_singles/01-vbl_basics.nes ppu_vbl_nmi/rom_singles/03-vbl_clear_time.nes \
  apu_test/rom_singles/1-len_ctr.nes apu_test/rom_singles/2-len_table.nes apu_test/rom_singles/3-irq_flag.nes \
  oam_read/oam_read.nes sprite_hit_tests_2005.10.05/01.basics.nes; do
  [ -f "$(basename "$f")" ] || curl -fsSLO "$B/$f"
done
echo "Test ROM'ları hazır: $(pwd)"
