//go:build cgo

// Package main builds Xray-core as a C shared library so it can be loaded
// in-process (e.g. from Java via JNA) instead of being run as a separate
// executable.
//
// IMPORTANT — loading a Go c-shared library inside a host process (JVM) is
// fragile: the Go runtime installs its own signal handlers during
// initialisation, which can clash with the host's (the JVM installs handlers
// for SIGSEGV/SIGBUS/SIGILL/SIGTRAP for its own use). On some kernels this
// shows up as a hard crash inside x_cgo_sigaction before any of our code runs.
//
// Mitigations applied at build time (see .github/workflows/build.yml):
//   - GOAMD64=v1 + -march=x86-64, so no AVX/FMA leaks onto older CPUs
//   - -tags netgo,osusergo, so cgo is not used for DNS/user lookups
//
// Mitigations applied at runtime (below): the exported entrypoints keep no Go
// goroutines of their own and never call into the host's signal machinery.
//
// Exported symbols:
//
//	int StartXray(const char* configJSON);  // 0 = ok
//	int StopXray(void);                     // 0 = ok
//	const char* XrayLastError(void);        // last error message, DO NOT free
//	const char* XrayVersion(void);          // Xray version string
//	int XrayRunning(void);                  // 1 = running, 0 = stopped
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unsafe"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Registers every inbound/outbound/app through init(). Without this the
	// config parses but no protocol is actually available.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu         sync.Mutex
	instance   *core.Instance
	lastErr    string
	lastErrPtr *C.char
	errMu      sync.Mutex
)

// setErr stores the last error both as a Go string and as a C string.
// The C string is intentionally never freed by the caller: it is owned by
// this library and reused on the next call.
func setErr(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)

	errMu.Lock()
	defer errMu.Unlock()

	lastErr = msg
	if lastErrPtr != nil {
		C.free(unsafe.Pointer(lastErrPtr))
		lastErrPtr = nil
	}
	lastErrPtr = C.CString(msg)
}

// parseConfig accepts the JSON produced by Xray's standard config loader
// (infra/conf) and, for already-serialised protobuf configs, a raw core.Config.
//
// The two schemas are NOT interchangeable: unmarshalling an infra/conf document
// into core.Config "succeeds" but yields an empty config with zero inbounds,
// which makes the core start happily and never listen on anything. So the
// fallback is only attempted when the input actually looks like a core.Config,
// and a successful fallback that contains no inbound/outbound is rejected.
func parseConfig(raw string) (*core.Config, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, errors.New("empty config")
	}

	cfg, confErr := serial.LoadJSONConfig(strings.NewReader(trimmed))
	if confErr == nil {
		if len(cfg.Inbound) == 0 && len(cfg.Outbound) == 0 {
			return nil, errors.New("config parsed but contains no inbound and no outbound")
		}
		return cfg, nil
	}

	// Only try the raw protobuf-JSON shape when it is plausibly that shape.
	if !strings.Contains(trimmed, `"inbound"`) && !strings.Contains(trimmed, `"outbound"`) {
		return nil, confErr
	}

	pc := &core.Config{}
	if err2 := json.Unmarshal([]byte(trimmed), pc); err2 == nil {
		if len(pc.Inbound) == 0 && len(pc.Outbound) == 0 {
			// Same silent-empty trap: report the original, more useful error.
			return nil, confErr
		}
		return pc, nil
	}

	return nil, confErr
}

//export StartXray
func StartXray(configJSON *C.char) C.int {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		setErr("already running")
		return 1
	}
	if configJSON == nil {
		setErr("config is nil")
		return 2
	}

	cfg, err := parseConfig(C.GoString(configJSON))
	if err != nil {
		setErr("parse config: %v", err)
		return 3
	}

	inst, err := core.New(cfg)
	if err != nil {
		setErr("core.New: %v", err)
		return 4
	}

	if err := inst.Start(); err != nil {
		_ = inst.Close()
		setErr("core.Start: %v", err)
		return 5
	}

	instance = inst
	setErr("")
	return 0
}

//export StopXray
func StopXray() C.int {
	mu.Lock()
	defer mu.Unlock()

	if instance == nil {
		return 0
	}
	if err := instance.Close(); err != nil {
		setErr("core.Close: %v", err)
		return 1
	}
	instance = nil
	return 0
}

//export XrayRunning
func XrayRunning() C.int {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil && instance.IsRunning() {
		return 1
	}
	return 0
}

//export XrayLastError
func XrayLastError() *C.char {
	errMu.Lock()
	defer errMu.Unlock()
	if lastErrPtr == nil {
		return C.CString("")
	}
	return lastErrPtr
}

//export XrayVersion
func XrayVersion() *C.char {
	return C.CString(core.Version())
}

//export FreeString
func FreeString(s *C.char) {
	if s != nil {
		C.free(unsafe.Pointer(s))
	}
}

func main() {}
