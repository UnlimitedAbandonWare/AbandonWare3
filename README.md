```markdown
# SyntaxAIWebModule — Minimal Web (Windows Socket Server, C++ DLL)

> 미니멀 웹(HTTP) 윈도우 소켓 서버를 C++ DLL로 구현한 실험용 프로젝트입니다.  
> 회사에서 MFC 기반 모듈을 만들기 전에 개인적으로 먼저 만들어 둔 프로토타입이며, 아직 “애기 상태(early-stage prototype)”라서 가볍게 공개합니다.  
> 제작 시점: 2023년 4월경.

---

## ✨ What is this?
- **Windows 전용** C++ **DLL**로 작성된 **미니멀 웹 서버**(HTTP server).
- **WinSock(Windows Sockets API)** 기반으로 **포트 80**에서 `bind/listen/accept` 루프를 돌며 요청을 처리합니다.
- **DLL 로드 시** 내부 스레드를 만들어 서버를 기동하도록 설계되어 있으며, 요청 라우팅과 정적 리소스 전송(이미지/JS/CSS)을 담당하는 간단한 핸들러를 포함합니다.
- CRC32 유틸리티(간단 무결성 체크용)가 포함되어 있습니다.

> 용어 정리  
> - **미니멀 웹(minimal web)**: 학습·실험 목적의 최소 기능 HTTP 서버  
> - **윈도우 소켓(WinSock)**: Windows의 소켓 API  
> - **DLL**: 동적 연결 라이브러리(동적 로딩해 코드 실행)

---

## 📁 Project Structure
레포의 핵심 파일과 역할은 다음과 같습니다.

```

SyntaxAIWebModule/
├─ SyntaxAIWebModule.vcxproj            # Visual Studio C++(DLL) 프로젝트
├─ SyntaxAIWebModule.vcxproj.filters
├─ SyntaxAIWebModule.vcxproj.user
├─ dllmain.h / dllmain.cpp              # DLL 진입점(DllMain) 및 초기화 스레드
├─ MainBASE.h / MainBASE.cpp            # 서버 소켓 생성/수락/처리 루프, 라우팅 보조
├─ cHandler.cpp                         # HTTP 응답/정적 파일 전송(이미지/CSS/JS)
├─ CRC32.h / CRC32.cpp                  # CRC32 계산 유틸
├─ pch.h / pch.cpp                      # Precompiled header 설정
├─ framework.h                          # Windows 헤더 전처리 등
└─ _Define.h                            # 스레드 스택/콜 규약 등 최소 정의(WSA 등)

````

- 소스 내 `addr.sin_port = htons(80); // 80 포트 사용`으로 기본 포트가 **80**으로 하드코딩되어 있습니다.
- 정적 리소스 예시 경로로 `.\\image\\h_copyright.png`, `/Scripts/menu.js`, `/Scripts/jquery-1.7.2.js` 등이 코드에 등장합니다(샘플 자원은 직접 추가 필요).

> 참고: 프로젝트는 `#pragma comment(lib, "Ws2_32.lib")`로 WinSock 링크를 지정합니다.

---

## 🧠 How it works (간단 동작 개요)
1. **DLL 로딩**: `DllMain(DLL_PROCESS_ATTACH)`에서 서버 초기화 스레드를 생성합니다.  
2. **WSA 초기화**: `WSAStartup(MAKEWORD(2,0), ...)`로 WinSock v2 초기화 후 소켓 생성.  
3. **바인딩/리스닝**: `INADDR_ANY:80`에 `bind` 후 `listen` 대기.  
4. **클라이언트 처리**: `accept`로 연결을 받고, 간단한 요청 파싱 후 `cHandler`로 분기:
   - 정적 PNG/CSS/JS 전송
   - 간단한 HTTP 헤더 작성(`HTTP/1.1 200 OK`, `Content-Type` 등)
5. **부가 기능**: `CRC32` 클래스로 파일/버퍼 무결성 체크 예시 포함.

> 설계 의도: **가벼운 러닝·테스트 베드**. 운영/보안/성능 최적화는 의도적으로 배제했습니다.

---

## 🔧 Build (Windows, Visual Studio)
- **요구 사항**
  - Windows 10/11
  - Visual Studio 2019/2022 (v142/v143 Toolset)
  - Windows SDK, C++ Desktop workload
- **빌드 절차**
  1. `SyntaxAIWebModule.vcxproj` 열기
  2. 구성: `Release | x64`(권장) 또는 `Debug`
  3. 빌드: `Build > Build Solution`
- **출력물**: `SyntaxAIWebModule.dll`

> PCH 사용(`pch.h/pch.cpp`) 프로젝트이므로, Visual Studio에서 기본 설정대로 빌드하면 됩니다.

---

## ▶️ Run (테스트 실행 예시)
이 프로젝트는 **DLL**이므로 **호스트 프로세스**가 필요합니다. 단순 테스트는 **DLL 로드만으로** 서버 스레드가 기동되도록 구성되어 있습니다. 아래는 최소 예시입니다.

```cpp
// MinimalHost.cpp (예시)
#include <windows.h>
#include <stdio.h>

int main() {
    HMODULE h = LoadLibraryA("SyntaxAIWebModule.dll");
    if (!h) {
        printf("LoadLibrary failed: %lu\n", GetLastError());
        return 1;
    }

    // 선택: 내보낸 함수가 있다면 호출 (존재하지 않을 수도 있음)
    // auto Startup = (int(*)(LPVOID))GetProcAddress(h, "Startup");
    // if (Startup) Startup(nullptr);

    printf("Server thread should be running (port 80). Press Enter to quit.\n");
    getchar();

    FreeLibrary(h);
    return 0;
}
````

* 실행 후 다른 콘솔에서:

  ```bash
  curl -v http://localhost/
  curl -v http://localhost/Scripts/menu.js
  ```
* **주의**: 포트 80은 시스템/보안 정책 또는 다른 서비스(IIS 등)와 충돌할 수 있습니다. 충돌 시 코드를 수정해 **8080** 등으로 변경하세요.

  * `MainBASE.cpp`의 `htons(80)`을 `htons(8080)`으로 교체.

---

## 📌 Status & Intent

* 상태: **Experiment/Prototype** (학습·참조용)
* 배경: **회사에서 MFC 모듈화 작업 이전**에 아이디어를 검증하려고 만든 **개인 실험물**
* 공개 이유: **초기 시도 흔적**이라도 남기고 싶었고, 나중에 **MFC/모듈러화 전환의 베이스**로 삼기 위함

---

## ⚠️ Limitations / Notes

* **프로덕션 미권장**: TLS/보안, 예외/에러 처리, 동시성 제어, 리소스 관리, 라우팅/파서 등이 매우 단순화되어 있습니다.
* **하드코딩**: 포트/경로/헤더/라우팅 일부가 코드에 직접 박혀 있습니다.
* **정적 리소스**: 예시 파일 경로만 존재할 수 있으므로, 필요 시 직접 자원을 배치하세요.
* **WinSock 전용**: Windows API에 의존(이식성 낮음).

---

## 🗺️ Roadmap (아이디어)

* 포트/라우팅 설정 외부화(INI/JSON)
* 간단한 템플릿/정적 파일 서버 정돈
* 멀티스레드 안전성/에러 처리 보강
* **MFC/모듈러 RAG** 환경에서의 내장/임베딩 인터페이스 정리
* 최소 단위의 테스트 코드 추가

---

## 🧾 License

* 미정(TBD). 오픈소스 라이선스를 적용하려면 `LICENSE` 파일을 추가하세요.
  (예: MIT, Apache-2.0 등. 선택 전 내부 정책 확인 권장)

---

## 🙌 Acknowledgements

* WinSock(WS2_32)
* Visual Studio C++ Toolset

```

**한계/반례 한 줄**: DLL 로드만으로 서버가 기동되는 설계는 샘플용으로 편하지만, 실제 애플리케이션 수명주기/보안 관점에선 명시적 초기화·종료 API를 제공하는 방식이 더 안전합니다.
```
