#!/usr/bin/env python3
"""Build app/src/main/res/font/modam.ttf from a licensed copy of ModamVF.ttf.

Modam ships as a variable font whose default instance is ExtraLight Condensed. Compose only
applies font variation settings on API 26+, so on API 24–25 every word in the app would render
at that default — hairline and squeezed. This retargets the default to Regular at normal width
while keeping both axes (wght 200–900, wdth 70–100) intact for the devices that can use them.

    pip install fonttools
    python3 tools/make-font.py "/path/to/Modam Pro/04 - Modam Variable/ModamVF.ttf"

Modam is a commercial face from fontiran.com. Regenerate from your own licensed copy; see the
Typography section of DEVELOPMENT.md before committing the result to a public repository.
"""

import os
import sys

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

DST = os.path.join("app", "src", "main", "res", "font", "modam.ttf")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    src = sys.argv[1]
    if not os.path.isfile(src):
        print(f"no such file: {src}")
        return 1

    font = TTFont(src)
    axes = {a.axisTag for a in font["fvar"].axes}
    if not {"wght", "wdth"} <= axes:
        print(f"expected wght and wdth axes, found: {sorted(axes)}")
        return 1

    # (min, default, max) — only the default moves; both ranges are preserved.
    instancer.instantiateVariableFont(
        font,
        {"wght": (200, 400, 900), "wdth": (70, 100, 100)},
        inplace=True,
        updateFontNames=True,
    )
    name = font["name"]
    name.setName("Modam", 1, 3, 1, 0x409)
    name.setName("Regular", 2, 3, 1, 0x409)
    name.setName("Modam", 4, 3, 1, 0x409)
    name.setName("Modam-Regular", 6, 3, 1, 0x409)

    os.makedirs(os.path.dirname(DST), exist_ok=True)
    font.save(DST)

    check = TTFont(DST)
    fvar = {a.axisTag: (a.minValue, a.defaultValue, a.maxValue) for a in check["fvar"].axes}
    print(f"wrote {DST} ({os.path.getsize(DST) // 1024} KB)")
    print(f"axes: {fvar}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
