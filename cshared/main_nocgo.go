//go:build !cgo

package main

// This file keeps `go build ./...` working when CGO is disabled.
// The c-shared library requires CGO, so there is nothing to build here.
func main() {}
