using System;

namespace ItemGuard.Execution {
    // Offline evidence-state coordinator. It records only facts already checked by a
    // guardian; it neither creates a process nor turns lifecycle state into launch proof.
    public sealed class GuardianProcessAdmission {
        public PaperLifecycle Lifecycle { get; private set; }
        public bool Failed { get; private set; }

        public GuardianProcessAdmission() { Lifecycle = new PaperLifecycle(); }

        bool Reject() { Failed = true; return false; }

        public bool TryRecordSpawnIntent() {
            if (Failed || !Lifecycle.Apply("SPAWN_INTENT")) return Reject();
            return true;
        }

        public bool TryRecordProcessCreatedSuspended() {
            if (Failed || !Lifecycle.Apply("PROCESS_CREATED_SUSPENDED")) return Reject();
            return true;
        }

        // The caller must independently establish each factual pre-release condition.
        public bool TryRecordAdmissionReady(bool assignedToOwnedJob, bool processIdentityBound,
            bool explicitPipesBound) {
            if (Failed || !assignedToOwnedJob || !processIdentityBound || !explicitPipesBound)
                return Reject();
            if (!Lifecycle.Apply("PROCESS_ADMISSION_READY")) return Reject();
            return true;
        }

        // This branch is available only while the worker is still suspended. The
        // caller must terminate through its held handle before reporting observation.
        public bool TryRecordSuspendedTerminationIntent() {
            if (Failed || !Lifecycle.Apply("SUSPENDED_TERMINATION_INTENT")) return Reject();
            return true;
        }

        // Unchanged primary-thread time is corroboration, not a substitute for the
        // guardian's actual terminate/wait/identity checks outside this pure layer.
        public bool TryRecordSuspendedTerminationObserved(bool primaryThreadTimeUnchanged) {
            if (Failed || !primaryThreadTimeUnchanged) return Reject();
            if (!Lifecycle.Apply("SUSPENDED_TERMINATION_OBSERVED")) return Reject();
            return true;
        }

        public bool TryRecordReleaseIntent() {
            if (Failed || !Lifecycle.Apply("EXECUTION_RELEASE_INTENT")) return Reject();
            return true;
        }

        // Binds the one-shot native ResumeThread attempt to the lifecycle transition.
        // The real guardian must terminate/wait and record a terminal failure outside
        // this offline coordinator when this returns false after the native call.
        public bool TryRelease(Func<uint> resumeThread) {
            if (resumeThread == null || !TryRecordReleaseIntent()) return false;
            uint previousSuspendCount;
            try {
                previousSuspendCount = resumeThread();
            } catch {
                return Reject();
            }
            return TryRecordReleaseObserved(previousSuspendCount);
        }

        // ResumeThread returns the prior suspend count. Only one is the sole guardian
        // release from CREATE_SUSPENDED; all other values remain execution-uncertain.
        public bool TryRecordReleaseObserved(uint previousSuspendCount) {
            if (Failed || !NativeSuspendedWorker.IsExactFirstResumeResult(previousSuspendCount))
                return Reject();
            if (!Lifecycle.Apply("EXECUTION_RELEASE_OBSERVED")) return Reject();
            return true;
        }
    }
}
