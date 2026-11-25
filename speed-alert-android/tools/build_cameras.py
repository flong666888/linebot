"""Convert the NPA "測速執法設置點" CSV into the compact asset the app loads.

Usage: python3 tools/build_cameras.py <downloaded.csv> app/src/main/assets/cameras.csv
Source: https://data.gov.tw/dataset/7320

Output columns: lat,lon,limit,h1,h2,section,address
  h1/h2 = allowed travel headings in degrees (0=N, 90=E); -1 = any direction.
  section = 1 for 區間測速 (average-speed section).
"""
import csv
import re
import sys

DIRS = {"東北": 45, "東南": 135, "西南": 225, "西北": 315, "北": 0, "東": 90, "南": 180, "西": 270}
DIR_RE = "東北|東南|西南|西北|北|東|南|西"


def headings(d: str):
    d = d.replace(" ", "")
    if "南北雙向" in d or "南北向" in d:
        return 0, 180
    if "東西雙向" in d or "東西向" in d:
        return 90, 270
    if "雙向" in d:
        return -1, -1
    m = re.search(f"({DIR_RE})[向往]({DIR_RE})", d)  # 北向南, 西南向東北
    if m:
        return DIRS[m.group(2)], -1
    m = re.search(f"往({DIR_RE})", d)  # 往南
    if m:
        return DIRS[m.group(1)], -1
    if "南下" in d:
        return 180, -1
    if "北上" in d:
        return 0, -1
    m = re.match(f"({DIR_RE})向", d)  # 南向 = southbound
    if m:
        return DIRS[m.group(1)], -1
    return -1, -1  # unknown (e.g. 往大溪方向): alert from any direction


def main(src, dst):
    rows = list(csv.DictReader(open(src, encoding="utf-8-sig")))
    out = []
    for r in rows:
        try:
            lat, lon, lim = float(r["Latitude"]), float(r["Longitude"]), int(r["limit"])
        except (ValueError, KeyError):
            continue  # header-description row or bad data
        if not (20 < lat < 27 and 117 < lon < 123):
            continue
        h1, h2 = headings(r["direct"])
        section = 1 if "區間" in r["direct"] or "區間" in r["Address"] else 0
        addr = (r["CityName"] + r["Address"]).replace(",", "，")
        out.append((round(lat, 6), round(lon, 6), lim, h1, h2, section, addr))
    with open(dst, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerows(out)
    print(f"wrote {len(out)} cameras")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
