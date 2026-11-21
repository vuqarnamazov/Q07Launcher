# APK-nı sabit açarla imzalamaq

GitHub Actions APK-nı yığır, sonra o, şəxsi açarla (repoda YOXDUR) yenidən imzalanır:

```
curl -sSLo apksig.jar https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar
javac -cp apksig.jar Sign.java
java -cp apksig.jar:. Sign app-release.apk Q07Launcher-signed.apk q07launcher.jks Q07_KS_PASS.txt
```

Açar: `Documents\Q07_imza_acari\` (q07launcher.jks + parol). Barmaq izi SHA-256:
`73:15:A4:42:DD:20:F3:2A:C1:D8:27:2B:B5:EF:02:EF:09:1F:31:D5:31:C6:B1:11:90:D3:27:96:08:B1:CC:EF`
