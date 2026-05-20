import os
import glob
import xml.etree.ElementTree as ET

strings_to_add = {
    "visual_overscan_use_visual_mode": {
        "fr": "Utiliser le mode visuel directionnel",
        "en": "Use directional visual mode",
        "de": "Visuellen Richtungsmodus verwenden",
        "es": "Usar el modo visual direccional",
        "it": "Utilizzare la modalità visiva direzionale",
        "tr": "Yönsel görsel modu kullan",
        "ru": "Использовать визуальный режим",
        "ar": "استخدام الوضع المرئي الاتجاهي",
        "kk": "Бағытты визуалды режимді қолдану",
        "uk": "Використовувати візуальний режим",
        "be": "Выкарыстоўваць візуальны рэжым",
        "uz": "Vizual rejimdan foydalanish"
    },
    "visual_overscan_height": {
        "fr": "↕ Hauteur",
        "en": "↕ Height",
        "de": "↕ Höhe",
        "es": "↕ Altura",
        "it": "↕ Altezza",
        "tr": "↕ Yükseklik",
        "ru": "↕ Высота",
        "ar": "↕ الارتفاع",
        "kk": "↕ Биіктігі",
        "uk": "↕ Висота",
        "be": "↕ Вышыня",
        "uz": "↕ Balandligi"
    },
    "visual_overscan_width": {
        "fr": "↔ Largeur",
        "en": "↔ Width",
        "de": "↔ Breite",
        "es": "↔ Anchura",
        "it": "↔ Larghezza",
        "tr": "↔ Genişlik",
        "ru": "↔ Ширина",
        "ar": "↔ العرض",
        "kk": "↔ Ені",
        "uk": "↔ Ширина",
        "be": "↔ Шырыня",
        "uz": "↔ Kengligi"
    }
}

base_dir = "/home/ccarre/app_byd/MyBYDApp/app/src/main/res"
value_dirs = glob.glob(os.path.join(base_dir, "values*"))

for vdir in value_dirs:
    strings_file = os.path.join(vdir, "strings.xml")
    if not os.path.exists(strings_file):
        continue
    
    lang = os.path.basename(vdir).replace("values-", "").replace("values", "fr")
    if lang == "fr": # default values/ uses French mostly based on context
        lang = "fr"
    
    try:
        tree = ET.parse(strings_file)
        root = tree.getroot()
        
        # Check if already present
        existing = [elem.get("name") for elem in root.findall("string")]
        
        changed = False
        for key, trans in strings_to_add.items():
            if key not in existing:
                elem = ET.Element("string")
                elem.set("name", key)
                elem.text = trans.get(lang, trans["en"])
                root.append(elem)
                changed = True
                
        if changed:
            ET.indent(tree, space="    ")
            tree.write(strings_file, encoding="utf-8", xml_declaration=True)
            print(f"Updated {strings_file}")
            
    except Exception as e:
        print(f"Error processing {strings_file}: {e}")

