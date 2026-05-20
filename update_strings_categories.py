import os
import glob
import xml.etree.ElementTree as ET

new_strings = {
    "category_navigation": {
        "fr": "🗺 Navigation",
        "en": "🗺 Navigation",
        "de": "🗺 Navigation",
        "es": "🗺 Navegación",
        "it": "🗺 Navigazione",
        "tr": "🗺 Navigasyon",
        "ru": "🗺 Навигация",
        "ar": "🗺 الملاحة",
        "kk": "🗺 Навигация",
        "uk": "🗺 Навігація",
        "be": "🗺 Навігацыя",
        "uz": "🗺 Navigatsiya"
    },
    "category_media": {
        "fr": "🎵 Média",
        "en": "🎵 Media",
        "de": "🎵 Medien",
        "es": "🎵 Multimedia",
        "it": "🎵 Media",
        "tr": "🎵 Medya",
        "ru": "🎵 Медиа",
        "ar": "🎵 وسائط",
        "kk": "🎵 Медиа",
        "uk": "🎵 Медіа",
        "be": "🎵 Медыя",
        "uz": "🎵 Media"
    }
}

base_dir = "/home/ccarre/app_byd/MyBYDApp/app/src/main/res"
value_dirs = glob.glob(os.path.join(base_dir, "values*"))

for vdir in value_dirs:
    strings_file = os.path.join(vdir, "strings.xml")
    if not os.path.exists(strings_file):
        continue
    
    lang = os.path.basename(vdir).replace("values-", "").replace("values", "fr")
    if lang == "fr":
        lang = "fr"
    
    try:
        tree = ET.parse(strings_file)
        root = tree.getroot()
        changed = False

        existing = [elem.get("name") for elem in root.findall("string")]
        
        for key, trans in new_strings.items():
            if key not in existing:
                elem = ET.Element("string")
                elem.set("name", key)
                elem.text = trans.get(lang, trans["en"])
                root.append(elem)
                changed = True
            else:
                for elem in root.findall("string"):
                    if elem.get("name") == key:
                        elem.text = trans.get(lang, trans["en"])
                        changed = True

        if changed:
            ET.indent(tree, space="    ")
            tree.write(strings_file, encoding="utf-8", xml_declaration=True)
            print(f"Updated {strings_file}")
            
    except Exception as e:
        print(f"Error processing {strings_file}: {e}")

