# 🔥 Simple GitHub Releases

**Just push a tag, get an APK. DONE.**

## Setup (One Time Only)

### 1. Encode your keystore
```bash
cd zdravo-android/app
base64 -i upload-key.keystore > keystore.txt
cat keystore.txt
```

### 2. Add GitHub Secrets
Go to your repo → Settings → Secrets → Actions:

- `KEYSTORE_BASE64`: Paste the base64 content from keystore.txt
- `KEYSTORE_PASSWORD`: Your keystore password
- `KEY_PASSWORD`: Your key password  
- `KEY_ALIAS`: Your key alias (usually `upload-key`)

## Release (Every Time)

### Method 1: Manual (GitHub UI)
1. Go to your repo → Actions tab
2. Click "Release APK" workflow
3. Click "Run workflow" button
4. Enter version (e.g., 1.0.0)
5. Click "Run workflow"

### Method 2: Git Tag
```bash
git tag v1.0.0
git push origin v1.0.0
```

**That's it.** GitHub will:
1. Build signed APK
2. Create release  
3. Upload `zdravo-{version}.apk`

## Download

Go to your repo → Releases → Download the APK

**NO CI. NO BULLSHIT. JUST RELEASES.**