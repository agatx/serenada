import { afterEach } from 'vitest';
import { __resetForegroundArbiterForTests } from '../packages/core/src/foregroundArbiter.js';

// The foreground media arbiter is a PROCESS singleton (one per JS execution
// context). Without a reset between tests, a lease/owning-mode held by one test
// (e.g. a `SerenadaCore.join()` whose session was not torn down) would make a
// later test fail with `ForegroundLeaseUnavailable`. Reset it after every test
// so the process-global state never leaks across the suite. This is the only
// global test wiring the multi-call arbiter requires.
afterEach(() => {
    __resetForegroundArbiterForTests();
});
