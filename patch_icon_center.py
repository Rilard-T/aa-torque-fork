#!/usr/bin/env python3
"""Патч 2б: пара «значок + значение» центрируется по прибору.

Запуск из корня репозитория:  python3 patch_icon_center.py   (после patch_icon_row.py)
Меняет app/src/main/res/layout/fragment_display.xml: у значения (valueElement) ширина
120dp -> wrap_content, выравнивание всегда по центру. Блок значок+значение становится
компактным и остаётся по центру прибора; при текстовой подписи вид прежний.
Повторный запуск безопасен.
"""
import sys

PATH = "app/src/main/res/layout/fragment_display.xml"
GRAV = 'android:gravity="@{(iconText == null || iconText.isEmpty()) ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER}"'


def main():
    try:
        with open(PATH, encoding="utf-8", newline="") as f:
            raw = f.read()
    except FileNotFoundError:
        sys.exit(f"ОШИБКА: нет файла {PATH}. Запускайте из корня репозитория.")
    crlf = "\r\n" in raw
    s = raw.replace("\r\n", "\n")

    if "valueRow" not in s:
        sys.exit("ОШИБКА: сначала примените patch_icon_row.py. Файл не изменён.")

    a = s.find('android:id="@+id/valueElement"')
    if a < 0:
        sys.exit("ОШИБКА: не нашёл valueElement. Файл не изменён.")
    b = s.find("/>", a)
    block = s[a:b]

    if 'android:layout_width="wrap_content"' in block and GRAV not in block:
        print("Патч уже применён, ничего не меняю.")
        return
    if block.count('android:layout_width="120dp"') != 1 or block.count(GRAV) != 1:
        sys.exit("ОШИБКА: valueElement выглядит не так, как ожидалось. Файл не изменён.")

    block = block.replace('android:layout_width="120dp"', 'android:layout_width="wrap_content"')
    block = block.replace(GRAV, 'android:gravity="center"')
    s = s[:a] + block + s[b:]

    with open(PATH, "w", encoding="utf-8", newline="") as f:
        f.write(s.replace("\n", "\r\n") if crlf else s)
    print("Готово: fragment_display.xml изменён.")


main()
