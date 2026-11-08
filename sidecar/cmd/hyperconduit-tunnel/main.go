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
	"syscall"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/tunnel"
)

func main() {
	// Inherited upstream debug flags write unstructured messages to stdout.
	// Reserve stdout for the versioned endpoint events below.
	_ = os.Unsetenv("HYSTERIA_BRUTAL_DEBUG")
	_ = os.Unsetenv("HYSTERIA_BBR_DEBUG")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Stdout, os.Stderr); err != nil {
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

func run(ctx context.Context, args []string, stdout, stderr io.Writer) error {
	if len(args) == 0 || (args[0] != "client" && args[0] != "server") {
		return errors.New("usage: hyperconduit-tunnel client|server --config <file.json>")
	}
	mode := args[0]
	flags := flag.NewFlagSet(mode, flag.ContinueOnError)
	flags.SetOutput(stderr)
	path := flags.String("config", "", "JSON configuration file (credentials are not accepted as CLI flags)")
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
	var endpoint *tunnel.Endpoint
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
		Version int    `json:"version"`
		Event   string `json:"event"`
		Mode    string `json:"mode"`
		Listen  string `json:"listen"`
	}{1, "ready", mode, endpoint.Addr().String()}); err != nil {
		return errors.New("cannot write ready event")
	}
	return endpoint.Wait()
}
