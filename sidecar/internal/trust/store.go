// Package trust owns the versioned SPKI trust database. No caller should write
// this file directly. Checks never add trust; Commit follows successful auth.
package trust

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"

	"golang.org/x/net/idna"

	"github.com/llll415/HyperConduit/sidecar/internal/privatefile"
)

const maxEntries = 4096

type ChangedError struct {
	Endpoint string
	Expected string
	Observed string
}

func (*ChangedError) Error() string { return "server identity changed" }

// StorageError deliberately omits file contents and paths from its message.
type StorageError struct{ Err error }

func (*StorageError) Error() string   { return "trust store unavailable" }
func (e *StorageError) Unwrap() error { return e.Err }

type Store struct{ path string }

func New(path string) *Store { return &Store{path: path} }

// Endpoint identifies the user-selected server, not a resolved IP or bridge.
func Endpoint(address string) (string, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || host == "" {
		return "", errors.New("invalid server endpoint")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return "", errors.New("invalid server port")
	}
	host = strings.TrimSuffix(host, ".")
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.IsUnspecified() {
			return "", errors.New("invalid wildcard endpoint")
		}
		host = ip.Unmap().String()
	} else {
		host, err = idna.Lookup.ToASCII(host)
		if err != nil || host == "" || strings.ContainsAny(host, " :/\\\t\r\n") {
			return "", errors.New("invalid server host")
		}
		host = strings.ToLower(host)
	}
	return net.JoinHostPort(host, strconv.Itoa(p)), nil
}

type database struct {
	Version int               `json:"version"`
	Pins    map[string]string `json:"pins"`
}

func validPin(pin string) bool {
	if !strings.HasPrefix(pin, "SHA256:") {
		return false
	}
	b, err := base64.RawStdEncoding.DecodeString(strings.TrimPrefix(pin, "SHA256:"))
	return err == nil && len(b) == 32 && "SHA256:"+base64.RawStdEncoding.EncodeToString(b) == pin
}

func (s *Store) load() (database, error) {
	b, err := privatefile.Read(s.path, 1<<20)
	if errors.Is(err, os.ErrNotExist) {
		return database{Version: 1, Pins: map[string]string{}}, nil
	}
	if err != nil {
		return database{}, err
	}
	if err := checkUnique(json.NewDecoder(bytes.NewReader(b))); err != nil {
		return database{}, errors.New("invalid trust database")
	}
	var db database
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(&db); err != nil {
		return database{}, errors.New("invalid trust database")
	}
	if err := d.Decode(new(any)); err != io.EOF || db.Version != 1 || db.Pins == nil || len(db.Pins) > maxEntries {
		return database{}, errors.New("invalid trust database")
	}
	for endpoint, pin := range db.Pins {
		canonical, err := Endpoint(endpoint)
		if err != nil || canonical != endpoint || !validPin(pin) {
			return database{}, errors.New("invalid trust entry")
		}
	}
	return db, nil
}

func match(endpoint, known, pin string) error {
	if known != "" && subtle.ConstantTimeCompare([]byte(known), []byte(pin)) != 1 {
		return &ChangedError{Endpoint: endpoint, Expected: known, Observed: pin}
	}
	return nil
}

func (s *Store) locked(ctx context.Context, fn func() error) error {
	err := privatefile.WithLock(ctx, s.path+".lock", fn)
	var changed *ChangedError
	if err == nil || errors.As(err, &changed) || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	return &StorageError{Err: err}
}

func (s *Store) Check(ctx context.Context, address, pin string) error {
	endpoint, err := Endpoint(address)
	if err != nil || !validPin(pin) {
		return errors.New("invalid trust candidate")
	}
	return s.locked(ctx, func() error {
		db, err := s.load()
		if err != nil {
			return err
		}
		return match(endpoint, db.Pins[endpoint], pin)
	})
}

// Commit checks again under the same cross-process lock used for the write.
// A concurrent first connection with a different key can never overwrite trust.
func (s *Store) Commit(ctx context.Context, address, pin string) error {
	endpoint, err := Endpoint(address)
	if err != nil || !validPin(pin) {
		return errors.New("invalid trust candidate")
	}
	return s.locked(ctx, func() error {
		db, err := s.load()
		if err != nil {
			return err
		}
		if err := match(endpoint, db.Pins[endpoint], pin); err != nil {
			return err
		}
		if db.Pins[endpoint] == pin {
			return nil
		}
		if len(db.Pins) >= maxEntries {
			return errors.New("trust entry limit reached")
		}
		db.Pins[endpoint] = pin
		return s.save(db)
	})
}

func (s *Store) Entries(ctx context.Context) (map[string]string, error) {
	var entries map[string]string
	err := s.locked(ctx, func() error {
		db, err := s.load()
		if err == nil {
			entries = db.Pins
		}
		return err
	})
	return entries, err
}

func (s *Store) Forget(ctx context.Context, address string) error {
	endpoint, err := Endpoint(address)
	if err != nil {
		return err
	}
	return s.locked(ctx, func() error {
		db, err := s.load()
		if err != nil {
			return err
		}
		if _, exists := db.Pins[endpoint]; !exists {
			return nil
		}
		delete(db.Pins, endpoint)
		return s.save(db)
	})
}

func (s *Store) save(db database) error {
	b, err := json.MarshalIndent(db, "", "  ")
	if err != nil {
		return err
	}
	return privatefile.Write(s.path, append(b, '\n'))
}
