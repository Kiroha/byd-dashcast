import os
import glob
import xml.etree.ElementTree as ET

new_strings = {
    "setting_boot_auto_start": {
        "fr": "Activer automatiquement la projection au démarrage (Auto-Boot)",
        "en": "Automatically start projection on startup (Auto-Boot)",
        "de": "Projektion beim Systemstart automatisch starten (Auto-Boot)",
        "es": "Iniciar proyección automáticamente al inicio (Auto-Boot)",
        "it": "Avviare automaticamente la proiezione all\\'avvio (Auto-Boot)",
        "tr": "Başlangıçta projeksiyonu otomatik başlat (Auto-Boot)",
        "ru": "Автоматически запускать проекцию при запуске (Auto-Boot)",
        "ar": "بدء العرض التلقائي عند بدء التشغيل (Auto-Boot)",
        "kk": "Іске қосылғанда проекцияны автоматты түрде бастау (Auto-Boot)",
        "uk": "Автоматично запускати проекцію під час запуску (Auto-Boot)",
        "be": "Аўтаматычна запускаць праекцыю пры запуску (Auto-Boot)",
        "uz": "Ishga tushganda proyeksiyani avtomatik boshlash (Auto-Boot)"
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

        # Add new key if not exists
        existing = [elem.get("name") for elem in root.findall("string")]
        if "setting_boot_auto_start" not in existing:
            elem = ET.Element("string")
            elem.set("name", "setting_boot_auto_start")
            elem.text = new_strings["setting_boot_auto_start"].get(lang, new_strings["setting_boot_auto_start"]["en"])
            root.append(elem)
            changed = True
        else:
            for elem in root.findall("string"):
                if elem.get("name") == "setting_boot_auto_start":
                    elem.text = new_strings["setting_boot_auto_start"].get(lang, new_strings["setting_boot_auto_start"]["en"])
                    changed = True

        if changed:
            ET.indent(tree, space="    ")
            tree.write(strings_file, encoding="utf-8", xml_declaration=True)
            print(f"Updated {strings_file}")
            
    except Exception as e:
        print(f"Error processing {strings_file}: {e}")

