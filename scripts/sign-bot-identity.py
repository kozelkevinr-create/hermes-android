#!/usr/bin/env python3
"""Marks a bot's Matrix identity as verified by signing it with your user-signing key.

Element X and Hermes Local show a bot as "verified" once your account has cross-signed the bot's
master key. Interactive emoji verification needs the bot to answer a request, which the Hermes
Matrix adapter cannot do, so this does the signing directly instead:

1. logs in as you (a temporary device that is logged out again at the end),
2. unlocks your user-signing key from secret storage with your recovery key,
3. signs the bot's master key and uploads the signature.

Run it on a machine that can reach the homeserver. It needs Python 3.8+ and `cryptography`.
Your password and recovery key are read with getpass: never put them in arguments, a file or a
shell variable. Nothing here is stored, logged or printed.

    python3 sign-bot-identity.py --homeserver URL --user @you:server --bot @bot:server
    python3 sign-bot-identity.py --self-test
"""
import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
RECOVERY_PREFIX = b"\x8b\x01"


class Fail(Exception):
    """A problem to show the user, without any secret in the message."""


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode().rstrip("=")


def unb64(text: str) -> bytes:
    return base64.b64decode(text + "=" * (-len(text) % 4))


def decode_recovery_key(text: str) -> bytes:
    """The 32-byte secret-storage key from a recovery key like 'EsT4 ...' (base58, parity-checked)."""
    chars = "".join(text.split())
    number = 0
    for ch in chars:
        index = ALPHABET.find(ch)
        if index < 0:
            raise Fail("That does not look like a recovery key.")
        number = number * 58 + index
    raw = number.to_bytes(35, "big") if number.bit_length() <= 35 * 8 else b""
    if len(raw) != 35 or raw[:2] != RECOVERY_PREFIX:
        raise Fail("That does not look like a recovery key.")
    parity = 0
    for byte in raw:
        parity ^= byte
    if parity != 0:
        raise Fail("The recovery key has a typo (its checksum does not match).")
    return raw[2:34]


def _derive(key: bytes, name: str):
    okm = HKDF(
        algorithm=hashes.SHA256(), length=64, salt=b"\0" * 32, info=name.encode(), backend=default_backend()
    ).derive(key)
    return okm[:32], okm[32:]


def _ctr(aes_key: bytes, iv: bytes, data: bytes) -> bytes:
    cipher = Cipher(algorithms.AES(aes_key), modes.CTR(iv), backend=default_backend()).encryptor()
    return cipher.update(data) + cipher.finalize()


def encrypt_secret(key: bytes, name: str, plaintext: bytes, iv: bytes) -> dict:
    aes_key, mac_key = _derive(key, name)
    ciphertext = _ctr(aes_key, iv, plaintext)
    return {
        "iv": b64(iv),
        "ciphertext": b64(ciphertext),
        "mac": b64(hmac.new(mac_key, ciphertext, hashlib.sha256).digest()),
    }


def decrypt_secret(key: bytes, name: str, blob: dict) -> bytes:
    aes_key, mac_key = _derive(key, name)
    ciphertext = unb64(blob["ciphertext"])
    expected = hmac.new(mac_key, ciphertext, hashlib.sha256).digest()
    if not hmac.compare_digest(expected, unb64(blob["mac"])):
        raise Fail("The recovery key does not unlock this account's secrets.")
    return _ctr(aes_key, unb64(blob["iv"]), ciphertext)


def key_matches_description(key: bytes, description: dict) -> bool:
    """True when [key] is the key the account's secret storage was set up with (or it cannot tell)."""
    if "iv" not in description or "mac" not in description:
        return True
    check = encrypt_secret(key, "", b"\0" * 32, unb64(description["iv"]))
    return hmac.compare_digest(unb64(check["mac"]), unb64(description["mac"]))


def canonical_json(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def sign_key_object(private: Ed25519PrivateKey, signer: str, signer_key_id: str, key_obj: dict) -> dict:
    """[key_obj] plus our signature under [signer]/[signer_key_id]; existing signatures are kept."""
    body = {k: v for k, v in key_obj.items() if k not in ("signatures", "unsigned")}
    signature = b64(private.sign(canonical_json(body)))
    signed = dict(key_obj)
    signatures = {u: dict(s) for u, s in key_obj.get("signatures", {}).items()}
    signatures.setdefault(signer, {})[signer_key_id] = signature
    signed["signatures"] = signatures
    return signed


def fingerprint(public_b64: str) -> str:
    return " ".join(public_b64[i : i + 4] for i in range(0, len(public_b64), 4))


class Homeserver:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method: str, path: str, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + "/_matrix/client/v3" + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            detail = ""
            try:
                detail = json.loads(error.read()).get("errcode", "")
            except Exception:
                pass
            raise Fail(f"The homeserver refused {method} {path.split('?')[0]} (HTTP {error.code} {detail}).")
        except urllib.error.URLError as error:
            raise Fail(f"Could not reach the homeserver: {error.reason}")


def run(args) -> None:
    user, bot = args.user, args.bot
    password = getpass.getpass(f"Password for {user} (not shown): ")
    recovery = getpass.getpass("Recovery key (not shown): ")
    key = decode_recovery_key(recovery)

    server = Homeserver(args.homeserver)
    login = server.call("POST", "/login", {
        "type": "m.login.password",
        "identifier": {"type": "m.id.user", "user": user},
        "password": password,
        "initial_device_display_name": "bot-identity signing (temporary)",
    })
    password = ""
    server.token = login["access_token"]
    try:
        quoted = urllib.parse.quote(user, safe="")
        default = server.call("GET", f"/user/{quoted}/account_data/m.secret_storage.default_key")["key"]
        description = server.call("GET", f"/user/{quoted}/account_data/m.secret_storage.key.{default}")
        if not key_matches_description(key, description):
            raise Fail("That recovery key is not the one this account's secret storage uses.")
        stored = server.call("GET", f"/user/{quoted}/account_data/m.cross_signing.user_signing")
        blob = stored["encrypted"].get(default)
        if blob is None:
            raise Fail("This account has no user-signing key in secret storage.")
        seed = unb64(decrypt_secret(key, "m.cross_signing.user_signing", blob).decode())
        private = Ed25519PrivateKey.from_private_bytes(seed)
        public = b64(private.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw))

        keys = server.call("POST", "/keys/query", {"device_keys": {user: [], bot: []}})
        published = keys.get("user_signing_keys", {}).get(user, {}).get("keys", {})
        if f"ed25519:{public}" not in published:
            raise Fail("The unlocked key does not match the account's published user-signing key.")
        master = keys.get("master_keys", {}).get(bot)
        if not master:
            raise Fail(f"{bot} has no cross-signing identity on the server.")
        master_id, master_pub = next(iter(master["keys"].items()))
        master_pub = master["keys"][master_id]

        print(f"\nBot:               {bot}")
        print(f"Its master key:    {fingerprint(master_pub)}")
        print(f"Signing with the user-signing key of {user}.")
        if not args.yes and input("Sign it? Type 'yes' to continue: ").strip().lower() != "yes":
            print("Cancelled; nothing was changed.")
            return

        signed = sign_key_object(private, user, f"ed25519:{public}", master)
        result = server.call("POST", "/keys/signatures/upload", {bot: {master_pub: signed}})
        if result.get("failures"):
            raise Fail("The homeserver rejected the signature.")
        print("Done: the bot's identity is now signed by you. Reopen the room in Element X to see it.")
    finally:
        try:
            server.call("POST", "/logout", {})
        except Fail:
            print("Note: could not log the temporary device out; remove 'bot-identity signing' "
                  "from your sessions list.", file=sys.stderr)


def self_test() -> None:
    """Round-trips the crypto helpers against themselves (no network, no real keys)."""
    key = os.urandom(32)
    parity = 0
    for byte in RECOVERY_PREFIX + key:
        parity ^= byte
    raw = RECOVERY_PREFIX + key + bytes([parity])
    number, text = int.from_bytes(raw, "big"), ""
    while number:
        number, rem = divmod(number, 58)
        text = ALPHABET[rem] + text
    spaced = " ".join(text[i : i + 4] for i in range(0, len(text), 4))
    assert decode_recovery_key(spaced) == key, "recovery key round trip"
    try:
        decode_recovery_key(spaced[:-1] + ("2" if spaced[-1] != "2" else "3"))
        raise AssertionError("a typo must be rejected")
    except Fail:
        pass

    seed = os.urandom(32)
    blob = encrypt_secret(key, "m.cross_signing.user_signing", b64(seed).encode(), os.urandom(16))
    assert unb64(decrypt_secret(key, "m.cross_signing.user_signing", blob).decode()) == seed, "secret round trip"
    try:
        decrypt_secret(os.urandom(32), "m.cross_signing.user_signing", blob)
        raise AssertionError("a wrong key must be rejected")
    except Fail:
        pass

    iv = os.urandom(16)
    description = {"iv": b64(iv), "mac": encrypt_secret(key, "", b"\0" * 32, iv)["mac"]}
    assert key_matches_description(key, description)
    assert not key_matches_description(os.urandom(32), description)

    private = Ed25519PrivateKey.from_private_bytes(seed)
    master = {"user_id": "@bot:hs", "usage": ["master"], "keys": {"ed25519:AAA": "AAA"},
              "signatures": {"@bot:hs": {"ed25519:AAA": "old"}}}
    signed = sign_key_object(private, "@me:hs", "ed25519:USK", master)
    assert signed["signatures"]["@bot:hs"] == {"ed25519:AAA": "old"}, "existing signatures kept"
    signature = unb64(signed["signatures"]["@me:hs"]["ed25519:USK"])
    private.public_key().verify(signature, canonical_json({k: v for k, v in master.items() if k != "signatures"}))
    assert canonical_json({"b": 1, "a": [True, None]}) == b'{"a":[true,null],"b":1}'
    print("self-test OK")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--homeserver", help="e.g. http://host:8008")
    parser.add_argument("--user", help="your Matrix ID, @you:server")
    parser.add_argument("--bot", help="the bot's Matrix ID, @bot:server")
    parser.add_argument("--yes", action="store_true", help="skip the confirmation question")
    parser.add_argument("--self-test", action="store_true", help="check the crypto helpers and exit")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not (args.homeserver and args.user and args.bot):
        parser.error("--homeserver, --user and --bot are required")
    try:
        run(args)
    except Fail as problem:
        sys.exit(f"Stopped: {problem}")


if __name__ == "__main__":
    main()
