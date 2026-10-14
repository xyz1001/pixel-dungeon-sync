"""Offline validation only. Does not import SDK, connect, or provision anything."""
import argparse
from bot import Binding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", help="Path to local binding JSON; no credentials")
    args = parser.parse_args()
    binding = Binding.load(args.config)
    print("Binding is enabled and complete." if binding.enabled else "Binding is disabled; no events will be processed.")
    print("Offline validation only; account/entitlement adapters and live Feishu delivery are not verified.")


if __name__ == "__main__":
    main()
