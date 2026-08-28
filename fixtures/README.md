# 픽스처

**라이브 세이브 파일을 테스트에 직접 쓰지 않는다.** 게임을 켜두면 스크림 한 판마다
내용이 바뀌어서 재현 가능한 테스트가 안 된다.

여기에 스냅샷을 뜨고, 파서 기대 출력을 `tests/baseline/*.json` 골든 파일로 함께 커밋한다.

```
fixtures/
├── slot_638683925954242004.tfm      스냅샷 (커밋 제외 — .gitignore)
├── common.data                      팀 이름표 스냅샷 (커밋 제외 — .gitignore)
└── README.md
```

원본 경로:

```
%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\slot_*.tfm
%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\common.data
```

**`common.data` 는 세이브가 아니라 프로필 공용 파일이다** (D55). 팀 이름이 여기 있고,
게임에서 팀을 커스터마이즈하면 그 자리에서 바뀐다. 슬롯 파일과 함께 스냅샷을 떠야
"그때 그 이름" 으로 테스트가 재현된다.

**`*.tfm_backup` 은 직전 저장본이다** (D28). 슬롯으로 잡으면 안 되지만,
"저장 사이에 무엇이 늘었는가"를 보는 증분 적재 테스트의 재료로는 쓸 수 있다.
