# 🌊 Google Flow Android MCP Server & Automation App

[![Build & Release APK](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg)](https://developer.android.com)
[![Protocol](https://img.shields.io/badge/MCP-Model%20Context%20Protocol-blue.svg)](https://modelcontextprotocol.io)

**Google Flow Android MCP**, Google Flow (Veo-2 ve Imagen) yapay zeka medya üretim platformunu Android üzerinde yerel bir **1x1 Görünmez Floating Overlay** ve **Gömülü Ktor MCP Sunucusu** ile çalıştıran, Termux / CLI ortamından otonom kontrol edilmesini sağlayan bir mobil otomasyon köprüsüdür.

---

## 🎯 Çözülen Problem ve Mimari

Termux veya Linux ARM64 ortamlarında masaüstü Chrome/Puppeteer/CDP çalıştırmak; grafik sunucusu eksikliği, Google 2FA giriş sorunları ve Android'in arka plan süreçlerini öldürmesi sebebiyle oldukça zordur.

Bu proje sorunu şu şekilde çözer:

```
┌─────────────────────────────────────────────────────────────┐
│                       ANDROID TELEFON                       │
│                                                             │
│   ┌─────────────────────┐         ┌─────────────────────┐   │
│   │   Termux / CLI      │         │   Flow Android App  │   │
│   │                     │         │                     │   │
│   │   • Antigravity /   │  HTTP   │  • Ktor MCP Server  │   │
│   │     Claude Code     │  (SSE)  │    (127.0.0.1:8765) │   │
│   │   • Batch Pipeline  │ ──────> │  • 1x1 Overlay View │   │
│   │   • flow_cli.py     │ <────── │  • Yerel WebView &  │   │
│   │                     │  JSON   │    Google Auth      │   │
│   │   (64 Görsel Batch) │  RPC    │  • DOM Scraper      │   │
│   └─────────────────────┘         └─────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

1. **Güvenli Google Oturumu:** Kullanıcı uygulama açıldığında yerel WebView üzerinden kendi Google hesabına (2FA, Passkey destekli) giriş yapar. Şifreler veya token'lar hiçbir yerde saklanmaz.
2. **1x1 Görünmez Floating Overlay:** WebView, `TYPE_APPLICATION_OVERLAY` izni ile 1x1 piksellik şeffaf bir pencereye küçültülür ve `ForegroundService` ile Android tarafından uyutulması engellenir.
3. **Gömülü MCP Sunucusu:** Android uygulaması içinde çalışan Ktor sunucusu `http://127.0.0.1:8765/sse` adresinden Model Context Protocol yayını yapar.
4. **Otonom Batch Pipeline:** Termux üzerinden tek komutla klasördeki onlarca görsel (örn: 64 PNG) sırayla Flow'a yüklenip, prompt ile yeniden üretilerek adlandırılmış şekilde kaydedilir.

---

## 🛠️ Desteklenen MCP Araçları (Tools)

| MCP Tool | Açıklama |
|---|---|
| `flow_status` | Google Flow oturum durumu, kredi ve bağlantı kontrolü |
| `flow_generate_image` | Metin promptu ile Imagen modeli üzerinden görsel üretimi |
| `flow_generate_image_with_references` | Referans görsel verilerek stil transferi / image-to-image üretimi |
| `flow_generate_video` | Veo-2 modeli ile yapay zeka video üretimi |
| `flow_discover_ui` | Sayfa DOM yapısını ve seçicileri inceleme |

---

## ⚡ Kurulum ve Kullanım

### 1. Antigravity / Claude Code MCP Yapılandırması
`~/.gemini/antigravity-cli/mcp.json` veya ilgili istemci yapılandırmanıza ekleyin:

```json
{
  "mcpServers": {
    "google-flow": {
      "url": "http://127.0.0.1:8765/sse"
    }
  }
}
```

### 2. Termux CLI Kontrolü
Uygulama arka planda çalışırken Termux üzerinden doğrudan kontrol edebilirsiniz:

```bash
# Durum kontrolü
python3 cli/flow_cli.py status

# Metinden görsel üretme
python3 cli/flow_cli.py image --prompt "Cyberpunk warrior in neon rain, 8k" --output ./warrior.png

# Referans görselle üretme
python3 cli/flow_cli.py ref-image --image ./sprite_01.png --prompt "Redesign in anime style" --output ./sprite_01_out.png

# Video üretme (Veo)
python3 cli/flow_cli.py video --prompt "Drone shot flying through futuristic Tokyo" --output ./tokyo.mp4
```

---

## 🔁 64 Görsel Toplu İşlem (Batch Pipeline)

Klasördeki 64 PNG görselini otomatik olarak Flow'a verip işlemek için:

```bash
python3 cli/process_batch.py \
  --input-dir ./my_sprites/ \
  --output-dir ./processed_sprites/ \
  --prompt "Pixel art style, high quality cyberpunk redesign" \
  --pattern "{name}_cyberpunk{ext}"
```

---

## 📦 APK İndirme & Derleme

Uygulama her `git push` sonrasında GitHub Actions üzerinde otomatik olarak derlenir ve GitHub Releases altında `.apk` olarak yayınlanır.

* **Releases Sayfası:** [GitHub Releases](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/releases)

Yerel derlemek için:
```bash
./gradlew assembleRelease
```

---

## 📄 Lisans
MIT License - TheOsmanYILDIRIM
