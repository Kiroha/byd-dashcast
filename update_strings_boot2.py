import os
import glob
import xml.etree.ElementTree as ET

new_strings = {
    "setting_boot_overscan": {
        "fr": "Activer la projection et restaurer l'Overscan au démarrage (Auto-Boot)",
        "en": "Start projection and restore Overscan on startup (Auto-Boot)",
        "de": "Projektion starten und Overscan beim Systemstart wiederherstellen (Auto-Boot)",
        "es": "Iniciar proyección y restaurar Overscan al inicio (Auto-Boot)",
        "it": "Avviare la proiezione e ripristinare l\\'Overscan all\\'avvio (Auto-Boot)",
        "tr": "Başlangıçta projeksiyonu başlat ve Overscan\\'i geri yükle (Auto-Boot)",
        "ru": "Запустить проекцию и восстановить Overscan при запуске (Auto-Boot)",
        "ar": "بدء العرض واستعادة Overscan عند بدء التشغيل (Auto-Boot)",
        "kk": "Іске қосылғанда проекцияны бастау және Overscan қалпына келтіру (Auto-Boot)",
        "uk": "Запустити проекцію та відновити Overscan під час запуску (Auto-Boot)",
        "be": "Запусціць праекцыю і аднавіць Overscan пры запуску (Auto-Boot)",
        "uz": "Ishga tushganda proyeksiyani boshlash va Overscan\\'ni tiklash (Auto-Boot)"
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

        for elem in root.findall("string"):
            if elem.get("name") == "setting_boot_overscan":
                elem.text = new_strings["setting_boot_overscan"].get(lang, new_strings["setting_boot_overscan"]["en"])
                changed = True

        if changed:
            ET.indent(tree, space="    ")
            tree.write(strings_file, encoding="utf-8", xml_declaration=True)
            print(f"Updated {strings_file}")
            
    except Exception as e:
        print(f"Error processing {strings_file}: {e}")

