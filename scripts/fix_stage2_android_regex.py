from pathlib import Path

p=Path('android/app/src/main/java/com/zoolchat/app/auth/AuthRepository.kt')
s=p.read_text()
start=s.index('    suspend fun completeTwoFactorLogin')
end=s.index('\n    suspend fun testBackend', start)
block=s[start:end]
old='        require(Regex("^\\d{8}$").matches(code)) { "رمز التحقق يجب أن يتكون من 8 أرقام" }'
# Kotlin raw strings avoid escape-sequence ambiguity and express the intended 8-digit code exactly.
new='        require(Regex("""^\\d{8}$""").matches(code)) { "رمز التحقق يجب أن يتكون من 8 أرقام" }'
if old not in block:
    # Tolerate the already malformed source representation produced by the earlier integration patch.
    malformed='        require(Regex("^\\d{8}$").matches(code)) { "رمز التحقق يجب أن يتكون من 8 أرقام" }'
    if malformed not in block:
        raise SystemExit('stage2 2FA code regex line not found')
    old=malformed
s=s[:start]+block.replace(old,new,1)+s[end:]
p.write_text(s)
print('fixed stage2 Android 2FA regex')
