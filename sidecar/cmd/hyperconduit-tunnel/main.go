package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sort"
	"syscall"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/trust"
	"github.com/llll415/HyperConduit/sidecar/internal/tunnel"
)

func main() {
	// Inherited upstream debug flags write unstructured messages to stdout.
	// Reserve stdout for the versioned endpoint events below.
	_ = os.Unsetenv("HYSTERIA_BRUTAL_DEBUG")
	_ = os.Unsetenv("HYSTERIA_BBR_DEBUG")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := runWithInput(ctx, os.Args[1:], os.Stdin, os.Stdout, os.Stderr); err != nil {
		var classified *tunnel.Error
		if errors.As(err, &classified) {
			_ = json.NewEncoder(os.Stderr).Encode(struct {
				Version int              `json:"version"`
				Event   string           `json:"event"`
				Code    tunnel.ErrorCode `json:"code"`
				Op      string           `json:"op"`
			}{1, "error", classified.Code, classified.Op})
		} else {
			fmt.Fprintln(os.Stderr, "hyperconduit-tunnel:", err)
		}
		os.Exit(1)
	}
}

// run keeps the original entry point for tests. It deliberately supplies no
// stdin, so callers of run can never enable managed mode by accident.
func run(ctx context.Context, args []string, stdout, stderr io.Writer) error {
	return runWithInput(ctx, args, nil, stdout, stderr)
}

// runWithInput is the testable entry point. Managed mode reads control input
// from stdin; the non-managed path never touches it.
func runWithInput(ctx context.Context, args []string, stdin io.Reader, stdout, stderr io.Writer) error {
	if len(args) > 0 && args[0] == "probe" {
		if len(args) != 1 {
			return errors.New("usage: hyperconduit-tunnel probe")
		}
		return runProbe(ctx, stdin, stdout)
	}
	if len(args) > 0 && args[0] == "trust" {
		return runTrust(ctx, args[1:], stdout, stderr)
	}
	if len(args) == 0 || (args[0] != "client" && args[0] != "server") {
		return errors.New("usage: hyperconduit-tunnel client|server --config <file.json> [--managed]")
	}
	mode := args[0]
	flags := flag.NewFlagSet(mode, flag.ContinueOnError)
	flags.SetOutput(stderr)
	path := flags.String("config", "", "JSON configuration file (credentials are not accepted as CLI flags)")
	managed := flags.Bool("managed", false, "read a shutdown command from stdin and stop when stdin closes")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if *path == "" || flags.NArg() != 0 {
		return errors.New("exactly one --config file is required")
	}
	c, err := config.Load(*path, mode)
	if err != nil {
		return err
	}
	if *managed {
		return runManaged(ctx, mode, c, stdin, stdout)
	}
	return runEndpoint(ctx, mode, c, stdout)
}

func runEndpoint(ctx context.Context, mode string, c config.Config, stdout io.Writer) error {
	return runEndpointObserved(ctx, mode, c, stdout, nil)
}

// runEndpointObserved is runEndpoint with an optional opt-in stats stream. A
// nil observe channel keeps the non-managed and legacy paths telemetry-free.
// Ready and stats are written by this same goroutine, so stdout has exactly one
// writer and the two event kinds can never interleave.
func runEndpointObserved(ctx context.Context, mode string, c config.Config, stdout io.Writer, observe <-chan struct{}) error {
	var endpoint *tunnel.Endpoint
	var err error
	if mode == "client" {
		endpoint, err = tunnel.StartClient(ctx, c)
	} else {
		endpoint, err = tunnel.StartServer(ctx, c)
	}
	if err != nil {
		return err
	}
	defer endpoint.Close()
	if err := json.NewEncoder(stdout).Encode(struct {
		Version     int    `json:"version"`
		Event       string `json:"event"`
		Mode        string `json:"mode"`
		Listen      string `json:"listen"`
		Fingerprint string `json:"fingerprint,omitempty"`
	}{1, "ready", mode, endpoint.Addr().String(), endpoint.Fingerprint()}); err != nil {
		return errors.New("cannot write ready event")
	}
	if observe == nil {
		return endpoint.Wait()
	}
	// Endpoint.Wait blocks, so a small waiter lets the stats loop also react to
	// endpoint exit and cancellation. The channel is buffered, so the waiter
	// always finishes even when the loop returns first.
	endpointDone := make(chan error, 1)
	go func() { endpointDone <- endpoint.Wait() }()
	return streamTelemetry(ctx, endpoint, endpointDone, observe, stdout)
}

func runTrust(ctx context.Context, args []string, stdout, stderr io.Writer) error {
	if len(args) == 0 || (args[0] != "list" && args[0] != "forget") {
		return errors.New("usage: hyperconduit-tunnel trust list|forget --config <client.json> [--server <host:port>]")
	}
	flags := flag.NewFlagSet("trust", flag.ContinueOnError)
	flags.SetOutput(stderr)
	path := flags.String("config", "", "Client configuration file")
	address := flags.String("server", "", "Server to forget")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if *path == "" || flags.NArg() != 0 {
		return errors.New("--config is required")
	}
	c, err := config.Load(*path, config.ModeClient)
	if err != nil {
		return err
	}
	if c.CAFile != "" {
		return errors.New("trust commands require TOFU mode")
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	store := trust.New(c.KnownServersFile)
	if args[0] == "forget" {
		if *address == "" {
			return errors.New("--server is required")
		}
		if err := store.Forget(ctx, *address); err != nil {
			return err
		}
		return json.NewEncoder(stdout).Encode(struct {
			Version int    `json:"version"`
			Event   string `json:"event"`
		}{1, "forgotten"})
	}
	if *address != "" {
		return errors.New("--server is only used with forget")
	}
	entries, err := store.Entries(ctx)
	if err != nil {
		return err
	}
	type entry struct {
		Endpoint    string `json:"endpoint"`
		Fingerprint string `json:"fingerprint"`
	}
	list := make([]entry, 0, len(entries))
	for endpoint, pin := range entries {
		list = append(list, entry{endpoint, pin})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Endpoint < list[j].Endpoint })
	return json.NewEncoder(stdout).Encode(struct {
		Version int     `json:"version"`
		Entries []entry `json:"entries"`
	}{1, list})
}
