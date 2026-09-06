package com.zoolchat.app.ui.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val code: String) {
    AR("ar"), EN("en");
    companion object { fun fromCode(code: String?) = if (code == "en") EN else AR }
}

object I18n {
    var language by mutableStateOf(AppLanguage.AR)

    private val en = mapOf(
        "مرحباً بك في ZoolChat" to "Welcome to ZoolChat",
        "زول قريب.. مهما بَعُد" to "Close, no matter the distance",
        "مراسلات، مكالمات صوت وفيديو، اجتماعات وقصص في مكان واحد. سنطلب الصلاحيات عند الحاجة فقط، ويمكنك تغييرها لاحقاً من الإعدادات." to "Messages, voice and video calls, meetings and stories in one place. Permissions are requested only when needed and can be changed later in Settings.",
        "متابعة" to "Continue", "إنشاء حساب" to "Create account", "تسجيل الدخول" to "Sign in", "استرداد الحساب" to "Recover account",
        "الاسم الظاهر" to "Display name", "اسم المستخدم" to "Username", "البريد الإلكتروني" to "Email", "رقم الجوال" to "Mobile number", "البريد الإلكتروني أو اسم المستخدم" to "Email or username", "كلمة المرور" to "Password", "كلمة المرور الجديدة" to "New password",
        "رمز الاسترداد" to "Recovery code", "رقم الهاتف (اختياري لاكتشاف جهات الاتصال)" to "Phone number (optional for contact discovery)",
        "إنشاء الحساب" to "Create account", "دخول" to "Sign in", "أهلاً بك في ZoolChat" to "Welcome to ZoolChat",
        "استرداد وتسجيل الدخول" to "Recover and sign in", "نسيت كلمة المرور؟" to "Forgot password?",
        "احفظ رمز الاسترداد" to "Save your recovery code", "حفظته — متابعة" to "Saved — continue", "رجوع" to "Back",
        "اعثر على زملائك في ZoolChat" to "Find your contacts on ZoolChat", "السماح بجهات الاتصال" to "Allow contacts", "ليس الآن" to "Not now",
        "لا تفوّت رسالة أو مكالمة" to "Don't miss a message or call", "السماح بالإشعارات" to "Allow notifications",
        "ابحث في المحادثات" to "Search chats", "ابدأ المحادثة" to "Start a conversation", "رسالة جديدة" to "New message", "لا توجد محادثات بعد" to "No chats yet",
        "الدردشات" to "Chats", "التحديثات" to "Updates", "المجتمعات" to "Communities", "المكالمات" to "Calls",
        "جهات اتصال ZoolChat" to "ZoolChat contacts", "ابحث باسم المستخدم @username أو الرقم الدولي" to "Search by @username or international number", "ابحث عن مستخدم باسم @username" to "Search for a user by @username",
        "لا توجد جهات اتصال مسجلة بعد. يمكنك البحث برقم الهاتف أو اسم المستخدم." to "No registered contacts yet. Search by phone number or username.", "لا توجد جهات اتصال مسجلة بعد. يمكنك البحث عن أي مستخدم عن طريق @username." to "No registered contacts yet. You can search for any user by @username.", "لا يوجد مستخدم مطابق" to "No matching user", "محادثة" to "Chat", "اتصال" to "Call",
        "سجل المكالمات الحقيقي" to "Real call history", "تحديث" to "Refresh", "صوت" to "Voice", "فيديو" to "Video", "الأخيرة" to "Recent", "لا توجد مكالمات بعد" to "No calls yet", "إعادة الاتصال" to "Call again",
        "تم الرفض" to "Declined", "لم يتم الرد" to "No answer", "مكالمة جارية" to "Active call", "مكالمة صادرة" to "Outgoing call", "مكالمة واردة" to "Incoming call", "مكالمة فيديو" to "Video call", "مكالمة صوتية" to "Voice call",
        "القصص والقنوات" to "Stories and channels", "قصتي" to "My story", "حالات حديثة" to "Recent stories", "القنوات" to "Channels", "آخر تحديثات التطبيق والميزات الجديدة" to "Latest app updates and new features",
        "مجتمعاتك" to "Your communities", "اجمع مجموعاتك في مكان واحد" to "Bring your groups together", "إنشاء مجتمع جديد" to "Create new community", "مجموعات فرعية، إعلانات وإدارة مركزية" to "Subgroups, announcements and central management", "العائلة الكبيرة" to "Big family", "أصدقاء الرياض" to "Riyadh friends", "فريق العمل" to "Work team",
        "اجتماعات سريعة وآمنة" to "Fast and secure meetings", "اجتماع جديد" to "New meeting", "ابدأ اجتماعاً الآن" to "Start a meeting now", "أدخل رمز الاجتماع" to "Enter meeting code", "انضم" to "Join", "القادم" to "Upcoming", "رابط واحد، غرفة انتظار ومشاركة شاشة" to "One link, waiting room and screen sharing", "مشاركة الشاشة" to "Screen sharing", "اجتماع فريق العمل" to "Team meeting",
        "الإعدادات" to "Settings", "البحث في الإعدادات" to "Search settings", "متاح" to "Available", "الحساب" to "Account", "الخصوصية" to "Privacy", "الإشعارات" to "Notifications", "التخزين والبيانات" to "Storage and data", "المظهر" to "Appearance", "الدعم" to "Support", "لغة التطبيق" to "App language", "العربية" to "Arabic", "المساعدة" to "Help", "معلومات عن التطبيق" to "About ZoolChat",
        "الأجهزة، تصدير البيانات، تسجيل الخروج، حذف الحساب" to "Devices, data export, sign out and delete account", "آخر ظهور، متصل الآن، مؤشرات القراءة" to "Last seen, online status and read receipts", "الخلفية، النسخ الاحتياطي، سجل الدردشة" to "Wallpaper, backup and chat history", "الرسائل والمجموعات والمكالمات" to "Messages, groups and calls", "إدارة التخزين وجودة الوسائط" to "Manage storage and media quality", "مركز المساعدة وسياسة الخصوصية" to "Help center and privacy policy",
        "الوضع الداكن" to "Dark mode", "الوضع الفاتح" to "Light mode", "حسب إعداد الجهاز" to "Use device setting", "فاتح" to "Light", "داكن" to "Dark", "حسب الجهاز" to "System default", "إغلاق" to "Close",
        "الحساب والأجهزة" to "Account and devices", "حساب ZoolChat" to "ZoolChat account", "الأجهزة المرتبطة" to "Linked devices", "نشط" to "Active", "إلغاء ربط الجهاز" to "Unlink device", "تصدير بياناتي" to "Export my data", "تسجيل الخروج" to "Sign out", "حذف حساب ZoolChat نهائياً" to "Delete ZoolChat account permanently", "حذف الحساب نهائياً؟" to "Delete account permanently?", "حذف نهائي" to "Delete permanently", "إلغاء" to "Cancel",
        "سيُحذف الحساب والرسائل والبيانات المرتبطة به نهائياً. لا يمكن التراجع عن هذه العملية." to "Your account, messages and associated data will be permanently deleted. This cannot be undone.",
        "التشفير ورمز الأمان" to "Encryption and security code", "هذه المحادثة تستخدم E2EE beta مباشر بين الجهازين." to "This chat uses beta end-to-end encryption directly between the two devices.", "بصمة جهازك:" to "Your device fingerprint:", "بصمة الطرف الآخر:" to "Peer fingerprint:", "بحث" to "Search", "بحث داخل المحادثة" to "Search in chat", "المزيد" to "More", "كتم الإشعارات" to "Mute notifications", "الرسائل المختفية" to "Disappearing messages", "الوسائط والروابط والمستندات" to "Media, links and documents", "إبلاغ" to "Report", "حظر" to "Block", "مسح الدردشة" to "Clear chat", "تصدير الدردشة" to "Export chat", "تحميل رسائل أقدم" to "Load older messages", "تم حذف هذه الرسالة" to "This message was deleted", "تم التعديل • " to "Edited • ", "إعادة المحاولة" to "Retry", "لا توجد نتائج" to "No results", "رسالة" to "Message", "صورة" to "Image", "ملف" to "File", "مستند" to "Document", "رسالة صوتية" to "Voice message", "جاري تحميل المعاينة…" to "Loading preview…", "جاري التحميل…" to "Loading…", "يكتب الآن…" to "typing…", "متصل الآن" to "online", "● جاري تسجيل رسالة صوتية — اضغط زر الميكروفون للإرسال" to "● Recording voice message — tap the microphone button to send",
        "⚠ تغيّر مفتاح الطرف الآخر. تحقق من البصمة قبل متابعة المحادثة الحساسة." to "⚠ The peer key changed. Verify the fingerprint before continuing a sensitive conversation.",
        "يُستخدم رقم الجوال لاكتشاف جهات اتصالك التي لديها ZoolChat. لن يظهر رقمك في نتائج البحث باسم المستخدم." to "Your mobile number is used to find phone contacts who also use ZoolChat. It is not shown in username search results.",
        "تعذر الاتصال بخدمة ZoolChat. تحقق من اتصال الإنترنت وحاول مرة أخرى." to "Could not connect to ZoolChat. Check your internet connection and try again.",
        "تعذر الاتصال بخدمة ZoolChat. تأكد من الإنترنت أو أن خدمة ZoolChat تعمل ثم حاول مرة أخرى." to "Could not connect to ZoolChat. Check your connection and try again.",
        "البريد الإلكتروني مستخدم بالفعل" to "This email is already in use", "رقم الجوال مستخدم بالفعل" to "This mobile number is already in use",
        "البريد الإلكتروني/اسم المستخدم أو كلمة المرور غير صحيحة" to "Email/username or password is incorrect", "البريد الإلكتروني غير صالح" to "Invalid email address",
        "رقم الجوال غير صالح" to "Invalid mobile number", "رقم الجوال مطلوب" to "Mobile number is required", "أدخل البريد الإلكتروني أو اسم المستخدم" to "Enter your email or username",
        "اختر الدولة ومفتاح الاتصال" to "Choose country and calling code", "ابحث عن دولة أو مفتاح" to "Search country or calling code",
        "مثال: Saudi Arabia أو +966" to "Example: Saudi Arabia or +966", "لا توجد دولة مطابقة" to "No matching country",
        "اختر الدولة" to "Choose country", "رقم الجوال بدون مفتاح الدولة" to "Mobile number without country code", "أدخل رقم الجوال بدون المفتاح" to "Enter mobile number without country code",
        "معلومات التطبيق" to "App information", "الإصدار" to "Version", "مالك التطبيق محمد الخيام" to "مالك التطبيق محمد الخيام", "App Owner: Mohamed Alkhyam" to "App Owner: Mohamed Alkhyam",

        // v0.32 profile, privacy, shared-content and group interactions.
        "من يمكنه رؤية معلوماتي الشخصية" to "Who can see my personal information",
        "آخر ظهور" to "Last seen", "الجميع" to "Everyone", "جهات اتصالي" to "My contacts", "لا أحد" to "Nobody",
        "صورة الملف الشخصي" to "Profile photo", "النبذة" to "About", "مؤشرات قراءة الرسائل" to "Read receipts",
        "عند إيقافها لن ترسل أو تستقبل علامتي القراءة في الدردشات الفردية." to "When disabled, read receipts are not sent or shown in direct chats.",
        "تعذر تحديث إعدادات الخصوصية" to "Could not update privacy settings",
        "الوسائط" to "Media", "الروابط" to "Links", "المستندات" to "Documents", "لا توجد عناصر" to "No items",
        "تعذر تحميل المحتوى المشترك" to "Could not load shared content", "تعذر فتح المرفق" to "Could not open attachment", "مرفق" to "Attachment",
        "مستخدم ZoolChat" to "ZoolChat user", "البحث في المحادثة" to "Search conversation", "مجموعات مشتركة" to "Groups in common",
        "حظر المستخدم" to "Block user", "الإبلاغ عن المستخدم" to "Report user", "السبب" to "Reason", "إرسال" to "Send",
        "عضو" to "Member", "أنت" to "You", "المالك" to "Owner", "مشرف" to "Admin", "تعيين مشرف" to "Make group admin", "إزالة الإشراف" to "Dismiss as admin", "إزالة من المجموعة" to "Remove from group",
        "إرسال رسالة خاصة" to "Message privately", "عرض الملف الشخصي" to "View profile", "عرض ملفي الشخصي" to "View my profile", "تعذر عرض الملف الشخصي" to "Could not display profile", "تعذر إكمال الطلب" to "Could not complete the request",
        "معلومات المجموعة" to "Group info", "رابط/رمز الدعوة" to "Invite link/code", "نسخ" to "Copy", "إضافة أعضاء" to "Add members", "إضافة" to "Add", "الأعضاء" to "Participants", "بحث عن عضو" to "Search participants",
        "المشرفون فقط يرسلون" to "Only admins can send", "المشرفون فقط يعدلون معلومات المجموعة" to "Only admins can edit group info", "المشرفون فقط يضيفون أعضاء" to "Only admins can add members", "الموافقة على الأعضاء الجدد" to "Approve new members",
        "الرسائل المؤقتة" to "Disappearing messages", "إيقاف" to "Off", "24 ساعة" to "24 hours", "7 أيام" to "7 days", "90 يومًا" to "90 days", "أسبوع" to "1 week", "8 ساعات" to "8 hours", "دائماً" to "Always", "تشغيل" to "On",
        "مغادرة المجموعة" to "Exit group", "الإبلاغ عن المجموعة" to "Report group", "المالك يجب أن ينقل الملكية أو يحذف المجموعة" to "The owner must transfer ownership or delete the group"
    )

    fun t(ar: String): String {
        if (language == AppLanguage.AR) return ar
        if (ar.startsWith("آخر ظهور ")) return "Last seen " + ar.removePrefix("آخر ظهور ")
        if (ar.startsWith("متصل: ")) return "Connected: " + ar.removePrefix("متصل: ")
        return en[ar] ?: ar
    }
}

fun t(ar: String): String = I18n.t(ar)
