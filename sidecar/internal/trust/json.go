package trust

import (
	"encoding/json"
	"errors"
	"io"
)

// Reject ambiguous files instead of accepting JSON's last-key-wins behavior.
func uniqueJSON(d *json.Decoder) error {
	token, err := d.Token()
	if err != nil {
		return err
	}
	delim, container := token.(json.Delim)
	if !container {
		return nil
	}
	switch delim {
	case '{':
		seen := map[string]bool{}
		for d.More() {
			key, err := d.Token()
			if err != nil {
				return err
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return errors.New("ambiguous trust database")
			}
			seen[name] = true
			if err := uniqueJSON(d); err != nil {
				return err
			}
		}
	case '[':
		for d.More() {
			if err := uniqueJSON(d); err != nil {
				return err
			}
		}
	default:
		return errors.New("invalid trust database")
	}
	_, err = d.Token()
	return err
}

func checkUnique(d *json.Decoder) error {
	if err := uniqueJSON(d); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("invalid trust database")
	}
	return nil
}
