import os
import glob
import xml.etree.ElementTree as ET

strings_to_add = {
    "setting_boot_overscan": {
        "fr": "Appliquer automatiquement cet Overscan au démarrage de la voiture (Auto-Boot)",
        "en": "Automatically apply this Overscan at car startup (Auto-Boot)",
        "de": "Diesen Overscan beim Fahrzeugstart automatisch anwenden (Auto-Boot)",
        "es": "Aplicar automáticamente este Overscan al encender el coche (Auto-Boot)",
        "it": "Applica automaticamente questo Overscan all'avvio dell'auto (Auto-Boot)",
        "tr": "Araç başladığında bu Overscan'i otomatik olarak uygula (Auto-Boot)",
        "ru": "Автоматически применять этот Overscan при запуске автомобиля (Auto-Boot)",
        "ar": "تطبيق Overscan هذا تلقائيًا عند بدء تشغيل السيارة (Auto-Boot)",
        "kk": "Бұл Overscan автокөлік іске қосылғанда автоматты түрде қолдану (Auto-Boot)",
        "uk": "Автоматично застосовувати цей Overscan при запуску автомобіля (Auto-Boot)",
        "be": "Аўтаматычна ўжываць гэты Overscan пры запуску аўтамабіля (Auto-Boot)",
        "uz": "Avtomobil ishga tushganda ushbu Overscan ni avtomatik ravishda qo'llash (Auto-Boot)"
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

