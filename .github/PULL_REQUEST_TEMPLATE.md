## Summary

<!-- What changed, and why? -->

## Testing

<!-- Which commands did you run, and what did you verify manually? -->

```bash
./protocol/tools/validate.sh
swift test --package-path macos
cd android && ./gradlew :core:test
```

## Checklist

- [ ] Related tests pass
- [ ] Documentation is updated for user-visible changes
- [ ] No secrets, real device names, or IP addresses are included
- [ ] If the wire protocol changed, `docs/protocol.md` and `protocol/fixtures/` were updated together
