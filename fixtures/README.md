# 픽스처

**라이브 세이브 파일을 테스트에 직접 쓰지 않는다.** 게임을 켜두면 스크림 한 판마다
내용이 바뀌어서 재현 가능한 테스트가 안 된다.

여기에 스냅샷을 뜨고, 파서 기대 출력을 `tests/baseline/*.json` 골든 파일로 함께 커밋한다.

```
fixtures/
├── slot_638683925954242004.tfm      스냅샷 (커밋 제외 — .gitignore)
└── README.md
```

원본 경로:

```
%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\slot_*.tfm
```

**`*.tfm_backup` 은 직전 저장본이다** (D28). 슬롯으로 잡으면 안 되지만,
"저장 사이에 무엇이 늘었는가"를 보는 증분 적재 테스트의 재료로는 쓸 수 있다.
