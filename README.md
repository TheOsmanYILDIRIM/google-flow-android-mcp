# 🌊 Google Flow Android MCP Server & Automation App (v2.0)

[![Build & Release APK](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg)](https://developer.android.com)
[![Protocol](https://img.shields.io/badge/MCP-Model%20Context%20Protocol-blue.svg)](https://modelcontextprotocol.io)

**Google Flow Android MCP v2.0**, Google Flow üzerindeki en güncel **Nano Banana 2** (Görsel) ve **Veo 3.1** (Video) modellerini Android üzerinde yerel bir **1x1 Görünmez Floating Overlay** ve **Gömülü Ktor MCP Sunucusu** ile çalıştıran mobil otomasyon köprüsüdür.

---

## 🚀 Güncel Özellikler & Desteklenen Modeller (v2.0)

* 🎨 **En Son Görsel Modelleri:** `Nano Banana 2` ve `Nano Banana`
* 🎬 **En Son Video Modeli:** `Veo 3.1`
* 📐 **Tüm En/Boy Oranları (Aspect Ratios):** `1:1`, `16:9`, `9:16`, `4:3`, `3:4`, `2:3`, `3:2`, `21:9`
* 🔢 **Çoklu Çıktı Üretimi (Batch Outputs):** Tek prompt ile aynı anda `1x`, `2x`, `3x` veya `4x` varyasyon üretimi
* 📁 **Proje Yönetimi & Kategorizasyon:** Proje listeleme, yeni proje alanı açma ve çıktıları organize etme
* 🔒 **Güvenli Google Oturumu:** Cihazın yerel WebView'i ile doğrudan Google hesabı (2FA, Passkey).
* 👻 **1x1 Görünmez Floating Overlay:** WebView, ekranda 1x1 şeffaf pencere olarak aktif kalarak işletim sisteminin JS/DOM süreçlerini dondurmasını engeller.
* ⚡ **Gömülü Ktor MCP Server:** `http://127.0.0.1:8765/sse` üzerinden Antigravity ve Claude Code CLI entegrasyonu.

---

## 🛠️ MCP Araçları (Tools)

| MCP Tool | Parametreler | Açıklama |
|---|---|---|
| `flow_status` | - | Oturum, bakiye, aktif modeller ve oranları denetler |
| `flow_generate_image` | `prompt`, `model`, `aspect_ratio`, `count`, `outputPath` | Nano Banana 2 ile belirtilen oranda ve çoklu sayıda (1-4x) görsel üretir |
| `flow_generate_image_with_references` | `prompt`, `imagePath`, `model`, `aspect_ratio`, `count`, `outputPath` | Referans görsel verilerek stil transferi / image-to-image üretimi |
| `flow_generate_video` | `prompt`, `model`, `aspect_ratio`, `outputPath` | Veo 3.1 modeli ile yapay zeka video üretimi |
| `flow_list_projects` | - | Flow üzerindeki kullanıcı projelerini listeler |
| `flow_create_project` | `name` | Yeni bir proje kategorisi oluşturur |
| `flow_discover_ui` | - | Sayfa DOM yapısını ve seçicileri inceler |

---

## ⚡ Termux CLI Kullanımı

### 1. Antigravity MCP Yapılandırması (`mcp.json`)
```json
{
  "mcpServers": {
    "google-flow": {
      "url": "http://127.0.0.1:8765/sse"
    }
  }
}
```

### 2. CLI Komutları

```bash
# Durum kontrolü
python3 cli/flow_cli.py status

# Nano Banana 2 ile 16:9 oranında ve 4 adet varyasyon üretme
python3 cli/flow_cli.py image \
  --prompt "Cyberpunk city alley in rain, neon reflections, 8k" \
  --model nano-banana-2 \
  --ratio 16:9 \
  --count 4 \
  --output ./city.png

# Veo 3.1 ile 9:16 (Reels/TikTok) formatında video üretme
python3 cli/flow_cli.py video \
  --prompt "Futuristic drone flying through clouds at sunset" \
  --model veo-3.1 \
  --ratio 9:16 \
  --output ./drone.mp4

# Projeleri listeleme
python3 cli/flow_cli.py projects
```

---

## 🔁 64 Görsel Toplu İşlem Pipeline'ı (Batch Runner)

Klasördeki 64 PNG dosyasını Nano Banana 2 modeliyle, istenen oranda (örn: 1:1 veya 16:9) sırayla işlemek için:

```bash
python3 cli/process_batch.py \
  --input-dir ./my_sprites/ \
  --output-dir ./processed_sprites/ \
  --model nano-banana-2 \
  --ratio 1:1 \
  --count 2 \
  --prompt "Pixel art style, high quality cyberpunk redesign" \
  --pattern "{name}_banana2{ext}"
```

---

## 📦 APK İndirme

* **Releases Sayfası:** [GitHub Releases](https://github.com/TheOsmanYILDIRIM/google-flow-android-mcp/releases)

---

## 📄 Lisans
MIT License - TheOsmanYILDIRIM
