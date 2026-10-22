# استفاده از پروژه‌های مرجع در MAXIMUS VPN — ۲۲ اکتبر ۲۰۲۶

این تغییر روی نسخهٔ اصلاح‌شدهٔ DNS جلسهٔ قبل ساخته شده است. سورس عمومی برنامه همچنان
1.0.2 است. آرشیو ورودی کد داخلی 31 داشت؛ main گیت‌هاب به 32 رسیده بود و هدف به‌روزرسانی حاضر 33 است. تنظیمات امضا تغییر نکرده است. در مرحلهٔ بررسی اولیه، ریلیز یا نصب سرور انجام نشده بود؛ انتشار نهایی در انتهای این گزارش ثبت شده است.

## بررسی هر هشت منبع

| پروژه و مرجع | یافته و کاربرد در ماکسیموس | وضعیت این تحویل |
|---|---|---|
| [Hashem Panel](https://github.com/pdnczone/hashem-panel) | مدیریت hub/spoke، GRE/FRP/Backhaul، بررسی سرویس داخل تونل و rollback دو سمت؛ مجوز AGPL-3.0 | مرجع طراحی Maximus Tunnel؛ ادغام مستقیم پنل، Cluster، GRE/FRP/Backhaul یا بخش Pro انجام نشده است. منطق صحت مسیر و rollback قبلی ماکسیموس حفظ شد. |
| [UAC Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) | تفکیک Xray/Tor/Psiphon-over-MASQUE، تست مسیر واقعی و وضعیت زنده | ماکسیموس از قبل Matrix Transport مشتق‌شده از Aether با pin و source offer مستقل دارد. نسخهٔ جدید UAC یا تنظیمات اختصاصی آن به برنامه منتقل نشده است. |
| [Hiddify Manager](https://github.com/hiddify/Hiddify-Manager) | مدیریت چرخهٔ نصب/پشتیبان/ارتقا؛ GPL-3.0 | adapter و UI موجود حفظ شده، نصب روی سرور اختصاصی و نسخهٔ pinشدهٔ 14.0.5 ادامه دارد. ادعای تست سرور زنده یا پشتیبانی restore اضافه نشده است. |
| [Byp](https://github.com/devlsam/Byp/blob/main/README_FA.md) | کلاینت ویندوز با Wintun و تفکیک برنامه‌ها، رابط ساده و پایش شبکه | برای UX و سیاست تفکیک مسیر مرجع است؛ کد ویندوز به Android قابل انتقال مستقیم نیست. LICENSE دارای شرایط BUSL-1.1 است؛ کدی از آن کپی نشده است. |
| [WhiteDNS-Android](https://github.com/WhiteDNS/WhiteDNS-Android) | جداسازی پروفایل/ریزالور و سنجش readiness بعد از انتقال احرازشده | مرجع رفتار و معیار پذیرش؛ LICENSE.MD استفاده در یک برنامهٔ مستقل را مجاز نمی‌کند. کد، APK، کلید، تصویر یا برند آن کپی نشده است. |
| [CottenDNS](https://github.com/WhiteDNS/CottenDNS) | موتور DNS با UDP/TCP/DoT/DoH؛ CottenMax اختیاری، کنترل نرخ و دامنهٔ پشتیبان؛ MIT با اجزای Soar/QUIC و مجوزهای خودشان | adapter اصلی ماکسیموس، import/export، editor، ثبت موتور، نگاشت مرحلهٔ DNS و دستور build بومی اضافه شد. هستهٔ انتخاب‌شده e50a43ef75ad10ae2ffbfa106d13c50de3e77624 است. باینری هر سه ABI در GitHub Actions ساخته شد و آزمون‌های هسته موفق شدند؛ اجرای واقعی روی گوشی و اپراتور هنوز تأیید نشده است. |
| [Soar-Server](https://github.com/WhiteDNS/Soar-Server) | پنل سمت سرور با بررسی delegation، آزمون واقعی HTTPS و rollback؛ Apache-2.0 | README، LICENSE و قرارداد معماری پنل بررسی شد. CottenMax از اجزای Soar استفاده می‌کند؛ این به معنی پشتیبانی مستقیم از همهٔ soar://ها یا نصب پنل Soar نیست. |
| [WhiteAestherMobile](https://github.com/WhiteDNS/WhiteAestherMobile) | MASQUE H2، اعتبارسنجی مسیر، برگشت محدود، API registration و هویت مستقل؛ AGPL-3.0 | مرجع رفتار/معیار آزمون برای Matrix Transport موجود؛ هسته یا کلیدهای برنامهٔ دیگر جایگزین نشد و جدیدترین Aether در این تحویل ادغام نشده است. |

README و ساختار فایل‌های هر هشت مخزن مستقیماً از GitHub بررسی شد. برای CottenDNS علاوه بر README،
رابط cmd/client، فایل تنظیمات، schema تنظیمات، منطق CottenMax و پیام readiness بررسی شدند.
برای Soar قرارداد docs/PANEL-DESIGN.md نیز خوانده شد. بررسی README معادل ممیزی تمام کد آن پروژه نیست.

## مسیر جدید CottenDNS در ماکسیموس

1. یک سرور CottenDNS با پشتیبانی CottenMax و دامنهٔ delegated معتبر لازم است. همان کلید و دامنهٔ سرور را استفاده کنید.
2. پروفایل به‌صورت صریح با حالت cottenmax وارد می‌شود؛ حالت legacy/XOR و downgrade خودکار فعال نشده است.
3. ماکسیموس فایل TOML و فهرست حداکثر شش ریزالور را در پوشهٔ خصوصی اجرای موتور می‌نویسد؛ فایل‌ها دسترسی 0600 دارند. کلید در argv و environment نیست.
4. برنامهٔ libcottendns.so روی SOCKS5 احرازشدهٔ 127.0.0.1 اجرا می‌شود؛ Xray مالک TUN، DNS و سیاست حفاظت باقی می‌ماند.
5. readiness نیازمند بازشدن پورت محلی و پیام `WD_PROGRESS phase=connected percent=100` است. هستهٔ pinشده این پیام را پس از احراز مسیر و probe انتقال تولید می‌کند. آزمون واقعی HTTP/TUN موجود ماکسیموس همچنان لازم است.
6. در نبود باینری، RuntimeCapabilities مسیر را unavailable اعلام می‌کند. ثبت adapter اثبات بسته‌بندی یا کارکرد روی گوشی نیست.

انتخاب transport: `auto`، `udp`، `tcp`، `dot` یا `doh`. انتخاب رمزگذاری‌شده در CottenMax دارای
اعتبارسنجی TLS است و به مسیر plaintext تنزل پنهانی ندارد. کشف گستردهٔ ریزالور و نمونه‌گیری CIDR
خاموش است؛ فقط ریزالورهای صریح پروفایل استفاده می‌شوند. محدودیت اولیهٔ کل پرسش‌ها 4 QPS و burst برابر 1 است؛
این مقدار محافظه‌کارانه می‌تواند throughput را کاهش دهد و برای هر شبکه نیازمند سنجش است.

تا سه دامنهٔ پشتیبان مجاز است؛ همه باید روی سرورهای سازگار با همان کلید تنظیم شده باشند.
تعویض سرور تضمینی برای انتقال نشست‌های TCP باز نیست.

## قالب لینک

قالب زیر مخصوص ماکسیموس است و معادل stormdns:// یا soar:// نیست:

```text
maximus-cotten://t.example.com?mode=cottenmax&key=URL_ENCODED_SERVER_KEY&transport=auto&resolver=1.1.1.1&resolver=8.8.8.8#MyTunnel
```

- کل لینک مانند رمز عبور حساس است و نباید در گزارش عمومی منتشر شود.
- link export رسمی از CottenDnsSidecar.toLink انجام می‌شود؛ ورودی در Import عادی قابل پذیرش است.
- editor جداگانه، domain/resolver/transport/backup/TLS name را ویرایش می‌کند. برای تعویض key، لینک جدید import کنید.
- ساخت خودکار پروفایل از پنل Soar/Hiddify، تبدیل schema برنامهٔ WhiteDNS، و نصب سرور Cotten از Install Center هنوز اضافه نشده‌اند.

## ساخت و پذیرش

`scripts/engines/cottendns.sh` source را با commit کامل fetch و بررسی می‌کند، وابستگی‌ها را با Go checksum database
و `go mod verify` بررسی می‌کند و build را با `-mod=readonly` انجام می‌دهد. build-engines.sh آن را برای ABIهای
پشتیبانی‌شده اجرا می‌کند. release gate نیز وجود libcottendns.so را برای همهٔ ABIهای الزامی می‌خواهد؛ APK ناقص نباید منتشر شود.

مجوزهای موتور در assets/licenses قرار دارند. adapter/UI جدید توسط ماکسیموس نوشته شده‌اند.

## موارد همچنان تکمیل‌نشده

- اجرای end-to-end CottenDNS/CottenMax با سرور سازگار، گوشی فیزیکی و شبکه‌های واقعی.
- پنل Hashem و carrierهای GRE/FRP/Backhaul، cluster و watchdog مستقل سمت سرور.
- اتصال مستقیم پنل Soar، پشتیبانی عمومی soar:// و کنترل حساب‌های آن.
- ساخت پروتکل مستقل Spoof Tunnel، raw-L3 allowlist backend و spoofed carriers.
- آزمون میدانی SNI/Host fronting، Clean-IP، DNS jitter، WARP TLS splitting و Matrix Transport.
- ترجمه و آزمون UI جدید، تغییر شبکه، screen-off و Android service lifecycle.

افزودن اسم یک پروژه به فهرست موتورهای فعال بدون adapter، باینری، تنظیمات معتبر و آزمون انتقال انجام نشده است.

## تأیید ادغام در گیت‌هاب

PR [#114](https://github.com/drfxai/MAXIMUS-VPN/pull/114) با commit `6e65afee7e9aa90f0682e718ff78176783915a3c` ادغام شد.

- [تمام تست‌های Android و lint](https://github.com/drfxai/MAXIMUS-VPN/actions/runs/39841452195): موفق.
- [ساخت CottenDNS برای سه ABI و آزمون‌های upstream](https://github.com/drfxai/MAXIMUS-VPN/actions/runs/39841452276): موفق.
- [آزمون‌های DNS/TLS و pacing](https://github.com/drfxai/MAXIMUS-VPN/actions/runs/39841452676): موفق.
- آزمون‌های Slipstream، ابزارهای سرور، aggregator، ربات و repository hygiene نیز در Checks موفق شدند.

انتشار امضاشدهٔ بیلد داخلی 33 در workflow موجود انجام شد؛ این مرحله با تأیید اپراتور/گوشی واقعی تفاوت دارد.

## نتیجهٔ انتشار نهایی

- [Signed Android release — run 86](https://github.com/drfxai/MAXIMUS-VPN/actions/runs/39843421451): موفق.
- نسخهٔ عمومی V1.0.2، کد داخلی Android برابر 33؛ commit انتشار `a0269bf22ec93e60729a8b31bd2ecff5c04ac586`.
- ساخت موتورهای بومی، تست‌ها، lint، ساخت ARM64/Universal، بررسی بسته‌بندی/امضا/checksum و نصب/اجرای Android 15 emulator موفق شدند.
- SHA-256 گواهی هر دو APK با انتشار قبلی یکسان است: `83352d116d4bb06fc9e8c5e6347633412da2197410fe45fdb48c1ad8e4a9dcfe`.
- [ریلیز رسمی و فایل‌های APK](https://github.com/drfxai/MAXIMUS-VPN/releases/tag/V1.0.2) به‌روزرسانی شد؛ update.json نیز Build 86 را اعلام می‌کند.
- آزمون اپراتورهای ایران و گوشی فیزیکی انجام نشده است؛ CottenMax همچنان beta و نیازمند سرور سازگار است.
