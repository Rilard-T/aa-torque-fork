#!/usr/bin/env python3
"""Строка «исполнитель — название» под центральным прибором (опция в настройках, медиа).
Запускать из корня репозитория: python3 patch_music.py
Независим от patch_sizes.py, идемпотентен. Если якорь не найден, ничего не записывается."""
import glob
import re
import sys

R = "app/src/main/"
files = {}
errors = []


def load(path):
    if path not in files:
        with open(path, encoding="utf-8") as f:
            files[path] = f.read()
    return files[path]


def sub(path, pattern, repl, marker, count=1):
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
sub(R + "proto/user_prefs.proto", r"(  uint32 darkenArt = 27;)",
    r"\1\n  bool showMusic = 32;", "showMusic")

# ---------- строки ----------
ru = {
    "pref_music_title": "Играющий трек",
    "pref_music_summary": "Показывать исполнителя и название под центральным прибором (нужен доступ к уведомлениям)",
}
en = {
    "pref_music_title": "Now playing",
    "pref_music_summary": "Show artist and title under the center gauge (needs notification access)",
}
for path, d in ((R + "res/values/strings.xml", en), (R + "res/values-ru/strings.xml", ru)):
    block = "".join('    <string name="%s">%s</string>\n' % (k, v) for k, v in d.items())
    sub(path, r"(</resources>)", block + r"\1", 'name="pref_music_title"')

# ---------- settings.xml ----------
checkbox = '''        <CheckBoxPreference
            android:key="showMusic"
            android:persistent="false"
            android:summary="@string/pref_music_summary"
            android:title="@string/pref_music_title" />
'''
sub(R + "res/xml/settings.xml",
    r'(    <PreferenceCategory android:title="@string/media">\n)',
    lambda m: m.group(1) + checkbox, 'android:key="showMusic"')

# ---------- AlbumArt: обновлять при смене названия/исполнителя, а не только обложки ----------
sub(R + "java/com/aatorque/stats/AlbumArt.kt",
    r"metaDataToArt\(it\)\.hashCode\(\)",
    "listOf(\n                        metaDataToArt(it)?.hashCode(),\n"
    "                        it?.getString(MediaMetadata.METADATA_KEY_TITLE),\n"
    "                        it?.getString(MediaMetadata.METADATA_KEY_ARTIST)\n"
    "                    ).hashCode()",
    "METADATA_KEY_TITLE")

# ---------- SettingsFragment ----------
sf = R + "java/com/aatorque/prefs/SettingsFragment.kt"
sub(sf, r"(    lateinit var blurArtPref: SeekBarPreference)",
    r"\1\n    lateinit var showMusicPref: CheckBoxPreference", "lateinit var showMusicPref")
sub(sf, r'(        darkenArtPref = findPreference\("darkenArtwork"\)!!)',
    r'\1\n        showMusicPref = findPreference("showMusic")!!', 'findPreference("showMusic")')
listener = '''        showMusicPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && !NotiService.isNotificationAccessEnabled(requireContext())) {
                startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            putExtra(
                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                NotiService::class.java.canonicalName
                            )
                        }
                    }
                )
            }
            updateDatastorePref { it.setShowMusic(enabled) }
            return@setOnPreferenceChangeListener true
        }
'''
sub(sf, r"(        blurArtPref\.isVisible = Build\.VERSION\.SDK_INT >= 31)",
    lambda m: listener + m.group(1), "showMusicPref.setOnPreferenceChangeListener")
sub(sf, r"(                darkenArtPref\.value = it\.darkenArt)",
    r"\1\n                showMusicPref.isChecked = it.showMusic", "showMusicPref.isChecked")

# ---------- DashboardFragment ----------
df = R + "java/com/aatorque/stats/DashboardFragment.kt"
collector = '''
        registerWithView {
            data -> data.map {
                it.showMusic
            }.distinctUntilChanged().collect {
                binding.showMusic = it
                binding.musicInfo.isSelected = true
            }
        }'''
sub(df, r"(this@DashboardFragment::updateScale\s*\)\s*\})",
    lambda m: m.group(1) + collector, "binding.showMusic = it")
sub(df, r"(    override suspend fun onMediaChanged\(medadata: MediaMetadata\?\) \{\n)",
    r"\1        binding.musicText = musicLine(medadata)\n", "binding.musicText = musicLine")
func = '''    private fun musicLine(m: MediaMetadata?): String {
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        return listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" — ")
    }

'''
sub(df, r"(    private fun updateScale\(largeCenter: Boolean\) \{)",
    lambda m: func + m.group(1), "private fun musicLine")

# ---------- layout ----------
textview = '''                <TextView
                    android:id="@+id/musicInfo"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:elevation="2dp"
                    android:ellipsize="marquee"
                    android:focusable="false"
                    android:gravity="center"
                    android:marqueeRepeatLimit="marquee_forever"
                    android:singleLine="true"
                    android:text="@{musicText}"
                    android:textColor="@android:color/white"
                    android:textSize="18sp"
                    android:visibility="@{(showChart || !showMusic || musicText == null || musicText.isEmpty()) ? View.GONE : View.VISIBLE}"
                    app:layout_constraintLeft_toLeftOf="@id/gaugeCenter"
                    app:layout_constraintRight_toRightOf="@id/gaugeCenter"
                    app:layout_constraintTop_toBottomOf="@id/gaugeCenter" />

'''
dash_files = sorted(glob.glob(R + "res/layout*/fragment_dashboard.xml"))
if not dash_files:
    errors.append("не найден fragment_dashboard.xml")
for p in dash_files:
    sub(p, r'(        <variable\s+name="minMaxBelow"\s+type="Boolean"\s*/>)',
        r'\1\n\n        <variable\n            name="musicText"\n            type="String" />\n\n'
        r'        <variable\n            name="showMusic"\n            type="boolean" />',
        'name="musicText"')
    sub(p, r'(                <androidx\.fragment\.app\.FragmentContainerView\s+android:id="@\+id/display1")',
        lambda m: textview + m.group(1), 'android:id="@+id/musicInfo"')

if errors:
    print("\nОШИБКИ, ничего не записано:")
    for e in errors:
        print(" -", e)
    sys.exit(1)

for path, text in files.items():
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
print("\nГотово. Изменено файлов: %d" % len(files))
