#!/usr/bin/env python3
"""Экран «Размеры элементов»: масштаб приборов, значений и подписей, скрытие верхней строки.
Запускать из корня репозитория: python3 patch_sizes.py
Идемпотентен. Если хоть один якорь не найден, ничего не записывается."""
import glob
import re
import sys

R = "app/src/main/"
files = {}   # path -> text
errors = []


def load(path):
    if path not in files:
        with open(path, encoding="utf-8") as f:
            files[path] = f.read()
    return files[path]


def sub(path, pattern, repl, marker, count=1):
    """Заменяет по регулярке; marker — строка, наличие которой значит «уже сделано»."""
    text = load(path)
    if marker in text:
        print("уже есть:", path, "->", marker)
        return
    new, n = re.subn(pattern, repl, text, count=count, flags=re.S)
    if n == 0:
        errors.append("не найден якорь в %s: %s" % (path, pattern[:60]))
        return
    files[path] = new
    print("правка:", path, "->", marker)


# ---------- proto ----------
sub(R + "proto/user_prefs.proto",
    r"(  uint32 darkenArt = 27;)",
    r"\1\n  uint32 gaugeScale = 28;\n  uint32 valueScale = 29;\n  uint32 labelScale = 30;\n  bool hideHeader = 31;",
    "gaugeScale")

# ---------- строки ----------
ru = {
    "pref_sizes": "Размеры элементов",
    "pref_sz_gauge": "Размер приборов, %",
    "pref_sz_value": "Размер значений в окошках, %",
    "pref_sz_label": "Размер подписей в окошках, %",
    "pref_sz_header": "Скрыть верхнюю строку",
    "pref_sz_header_desc": "Строка статуса подключения показывается только пока идёт подключение",
}
en = {
    "pref_sizes": "Element sizes",
    "pref_sz_gauge": "Gauge size, %",
    "pref_sz_value": "Tile value size, %",
    "pref_sz_label": "Tile label size, %",
    "pref_sz_header": "Hide top row",
    "pref_sz_header_desc": "The connection status row is shown only while connecting",
}
for path, d in ((R + "res/values/strings.xml", en), (R + "res/values-ru/strings.xml", ru)):
    block = "".join('    <string name="%s">%s</string>\n' % (k, v) for k, v in d.items())
    sub(path, r"(</resources>)", block + r"\1", 'name="pref_sizes"')

# ---------- settings.xml ----------
category = '''    <PreferenceCategory
        android:singleLineTitle="false"
        android:title="@string/pref_sizes">
        <SeekBarPreference
            android:defaultValue="100"
            android:key="gaugeScale"
            android:max="150"
            android:persistent="false"
            android:title="@string/pref_sz_gauge"
            app:min="50"
            app:showSeekBarValue="true" />
        <SeekBarPreference
            android:defaultValue="100"
            android:key="valueScale"
            android:max="150"
            android:persistent="false"
            android:title="@string/pref_sz_value"
            app:min="50"
            app:showSeekBarValue="true" />
        <SeekBarPreference
            android:defaultValue="100"
            android:key="labelScale"
            android:max="150"
            android:persistent="false"
            android:title="@string/pref_sz_label"
            app:min="50"
            app:showSeekBarValue="true" />
        <CheckBoxPreference
            android:defaultValue="false"
            android:key="hideHeader"
            android:persistent="false"
            android:summary="@string/pref_sz_header_desc"
            android:title="@string/pref_sz_header" />
    </PreferenceCategory>

'''
sub(R + "res/xml/settings.xml",
    r'(    <PreferenceCategory android:title="@string/media">)',
    lambda m: category + m.group(1),
    'android:key="gaugeScale"')

# ---------- SettingsViewModel ----------
sub(R + "java/com/aatorque/prefs/SettingsViewModel.kt",
    r"(    val minMaxBelow = MutableLiveData<Boolean>\(\))",
    r"\1\n    val valueScale = MutableLiveData(1f)\n    val labelScale = MutableLiveData(1f)",
    "valueScale")

# ---------- SettingsFragment ----------
sf = R + "java/com/aatorque/prefs/SettingsFragment.kt"
sub(sf, r"(    lateinit var blurArtPref: SeekBarPreference)",
    r"\1\n    lateinit var gaugeScalePref: SeekBarPreference\n"
    r"    lateinit var valueScalePref: SeekBarPreference\n"
    r"    lateinit var labelScalePref: SeekBarPreference\n"
    r"    lateinit var hideHeaderPref: CheckBoxPreference",
    "gaugeScalePref: SeekBarPreference")
sub(sf, r'(        darkenArtPref = findPreference\("darkenArtwork"\)!!)',
    r'\1\n        gaugeScalePref = findPreference("gaugeScale")!!\n'
    r'        valueScalePref = findPreference("valueScale")!!\n'
    r'        labelScalePref = findPreference("labelScale")!!\n'
    r'        hideHeaderPref = findPreference("hideHeader")!!',
    'findPreference("gaugeScale")')
listeners = '''        gaugeScalePref.setOnPreferenceChangeListener { _, newValue ->
            updateDatastorePref { it.setGaugeScale(newValue as Int) }
            return@setOnPreferenceChangeListener true
        }
        valueScalePref.setOnPreferenceChangeListener { _, newValue ->
            updateDatastorePref { it.setValueScale(newValue as Int) }
            return@setOnPreferenceChangeListener true
        }
        labelScalePref.setOnPreferenceChangeListener { _, newValue ->
            updateDatastorePref { it.setLabelScale(newValue as Int) }
            return@setOnPreferenceChangeListener true
        }
        hideHeaderPref.setOnPreferenceChangeListener { _, newValue ->
            updateDatastorePref { it.setHideHeader(newValue as Boolean) }
            return@setOnPreferenceChangeListener true
        }
'''
sub(sf, r"(        blurArtPref\.isVisible = Build\.VERSION\.SDK_INT >= 31)",
    lambda m: listeners + m.group(1), "gaugeScalePref.setOnPreferenceChangeListener")
sub(sf, r"(                darkenArtPref\.value = it\.darkenArt)",
    r"\1\n                gaugeScalePref.value = if (it.gaugeScale == 0) 100 else it.gaugeScale\n"
    r"                valueScalePref.value = if (it.valueScale == 0) 100 else it.valueScale\n"
    r"                labelScalePref.value = if (it.labelScale == 0) 100 else it.labelScale\n"
    r"                hideHeaderPref.isChecked = it.hideHeader",
    "gaugeScalePref.value")

# ---------- TorqueDisplay ----------
sub(R + "java/com/aatorque/stats/TorqueDisplay.kt",
    r"(        binding\.showBottom = isBottomDisplay\n)",
    r"\1        settingsViewModel.valueScale.observe(viewLifecycleOwner) { binding.valueScale = it }\n"
    r"        settingsViewModel.labelScale.observe(viewLifecycleOwner) { binding.labelScale = it }\n",
    "binding.valueScale")

# ---------- DashboardFragment ----------
df = R + "java/com/aatorque/stats/DashboardFragment.kt"
collector = '''
        registerWithView {
            data -> data.map {
                listOf(it.gaugeScale, it.valueScale, it.labelScale, if (it.hideHeader) 1 else 0)
            }.distinctUntilChanged().collect {
                applySizes(it[0], it[1], it[2], it[3] == 1)
            }
        }'''
sub(df, r"(this@DashboardFragment::updateScale\s*\)\s*\})",
    lambda m: m.group(1) + collector, "applySizes(it[0]")
func = '''    private fun applySizes(gauge: Int, value: Int, label: Int, hideHeader: Boolean) {
        fun pct(v: Int) = (if (v == 0) 100 else v) / 100f
        binding.gaugeScale = pct(gauge)
        binding.hideHeader = hideHeader
        settingsViewModel.valueScale.value = pct(value)
        settingsViewModel.labelScale.value = pct(label)
    }

'''
sub(df, r"(    private fun updateScale\(largeCenter: Boolean\) \{)",
    lambda m: func + m.group(1), "private fun applySizes")

# ---------- layouts (все варианты, где есть файл) ----------
scale_g = '\n        android:scaleX="@{gaugeScale == 0f ? 1f : gaugeScale}"\n        android:scaleY="@{gaugeScale == 0f ? 1f : gaugeScale}"'
dash_files = sorted(glob.glob(R + "res/layout*/fragment_dashboard.xml"))
disp_files = sorted(glob.glob(R + "res/layout*/fragment_display.xml"))
if not dash_files or not disp_files:
    errors.append("не найден fragment_dashboard.xml или fragment_display.xml")
for p in dash_files:
    sub(p, r'(        <variable\s+name="minMaxBelow"\s+type="Boolean"\s*/>)',
        r'\1\n\n        <variable\n            name="gaugeScale"\n            type="float" />\n\n'
        r'        <variable\n            name="hideHeader"\n            type="boolean" />',
        'name="gaugeScale"')
    for gid in ("gaugeLeft", "gaugeRight"):
        sub(p, r'(android:id="@\+id/%s")' % gid, lambda m: m.group(1) + scale_g,
            'id/%s"%s' % (gid, scale_g))
    # центральный прибор: умножаем существующий множитель 1.1 на общий масштаб
    sub(p, r'android:scaleX="@\{largeCenter \? 1\.1f : 1f\}"',
        'android:scaleX="@{(largeCenter ? 1.1f : 1f) * (gaugeScale == 0f ? 1f : gaugeScale)}"',
        "(largeCenter ? 1.1f : 1f) * (gaugeScale")
    text = load(p)
    if 'android:scaleY="@{(largeCenter' not in text:
        sub(p, r'android:scaleY="@\{largeCenter \? 1\.1f : 1f\}"',
            'android:scaleY="@{(largeCenter ? 1.1f : 1f) * (gaugeScale == 0f ? 1f : gaugeScale)}"',
            'android:scaleY="@{(largeCenter')
    sub(p, r'android:visibility="@\{status == 0 \? View\.INVISIBLE : View\.VISIBLE\}"',
        'android:visibility="@{status == 0 ? (hideHeader ? View.GONE : View.INVISIBLE) : View.VISIBLE}"',
        "hideHeader ? View.GONE")

scale_v = '\n            android:scaleX="@{valueScale == 0f ? 1f : valueScale}"\n            android:scaleY="@{valueScale == 0f ? 1f : valueScale}"'
scale_l = '\n                android:scaleX="@{labelScale == 0f ? 1f : labelScale}"\n                android:scaleY="@{labelScale == 0f ? 1f : labelScale}"'
for p in disp_files:
    sub(p, r'(    <variable name="showSide" type="Boolean" />)',
        r'\1\n    <variable name="valueScale" type="float" />\n    <variable name="labelScale" type="float" />',
        'name="valueScale"')
    sub(p, r'(android:id="@\+id/valueElement")', lambda m: m.group(1) + scale_v,
        'valueElement"' + scale_v)
    sub(p, r'(android:id="@\+id/labelElement")', lambda m: m.group(1) + scale_l,
        'labelElement"' + scale_l)
    sub(p, r'(android:id="@\+id/iconElement")', lambda m: m.group(1) + scale_l,
        'iconElement"' + scale_l)

if errors:
    print("\nОШИБКИ, ничего не записано:")
    for e in errors:
        print(" -", e)
    sys.exit(1)

for path, text in files.items():
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
print("\nГотово. Изменено файлов: %d" % len(files))
