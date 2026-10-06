"""Offline-first command-line composition. Radio use is always explicit."""

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from .languages import LANGUAGES
from .message import Message
from .service import send_message
from .transport import MeshtasticTransport, SimulatedMesh, validate_channel, validate_destination


def _print(value):
    print(json.dumps(value, ensure_ascii=False))


def parser():
    root = argparse.ArgumentParser(prog="phonemesh")
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("languages", help="List upstream-matched languages (not installed assets)")
    for name in ("simulate", "encode", "send"):
        command = commands.add_parser(name)
        command.add_argument("--text", required=True)
        command.add_argument("--language", required=True)
        if name == "simulate":
            command.add_argument("--loss", type=float, default=0)
            command.add_argument("--latency", type=float, default=0)
            command.add_argument("--timeout", type=float, default=1)
            command.add_argument("--seed", type=int, default=0)
        if name == "send":
            command.add_argument("--device", required=True)
            command.add_argument("--channel", required=True, type=int)
            command.add_argument("--to", required=True)
            command.add_argument("--confirm", action="store_true")
    decode = commands.add_parser("decode")
    decode.add_argument("--payload", required=True)
    decode.add_argument("--language", help="Required only for untagged stock messages")
    receive = commands.add_parser("receive", help="Receive one text; never automatically speak")
    receive.add_argument("--device", required=True)
    receive.add_argument("--channel", required=True, type=int)
    receive.add_argument("--timeout", type=float, default=30)
    receive.add_argument("--language", help="Language for untagged stock messages")
    transcribe = commands.add_parser("transcribe", help="Preview transcription; never transmits")
    transcribe.add_argument("--audio", type=Path, required=True)
    transcribe.add_argument("--model", type=Path, required=True)
    transcribe.add_argument("--language", required=True)
    synthesize = commands.add_parser("synthesize", help="Render received/preview text to local WAV")
    synthesize.add_argument("--payload", required=True)
    synthesize.add_argument("--language", help="Language for untagged stock messages")
    synthesize.add_argument("--model", type=Path, required=True)
    synthesize.add_argument("--output", type=Path, required=True)
    return root


def main(argv=None):
    options = parser()
    args = options.parse_args(argv)
    try:
        return _run(args)
    except (ValueError, PermissionError, OSError, ImportError, RuntimeError) as error:
        options.exit(2, f"phonemesh: {error}\n")


def _run(args):
    if args.command == "languages":
        _print([asdict(language) for language in LANGUAGES])
        return 0
    if args.command in ("encode", "simulate", "send"):
        message = Message(args.text, args.language)
        payload = message.encode()
        if args.command == "encode":
            _print({"payload": payload, "bytes": len(payload.encode("utf-8"))})
            return 0
        if args.command == "simulate":
            mesh = SimulatedMesh(loss=args.loss, latency=args.latency, seed=args.seed)
            sender, receiver = mesh.endpoint("sender"), mesh.endpoint("receiver")
            try:
                receipt = send_message(message, sender, "receiver", confirmed=True)
                incoming = receiver.receive(timeout=args.timeout)
                _print(
                    {
                        "mode": "simulated-text-only",
                        "receipt": asdict(receipt),
                        "received": asdict(Message.decode(incoming.payload)) if incoming else None,
                        "radio_bytes": len(payload.encode("utf-8")),
                    }
                )
                return 0 if incoming else 3
            finally:
                sender.close()
                receiver.close()
        # Refuse BEFORE opening hardware, even if other arguments are valid.
        if not args.confirm:
            raise PermissionError("Run encode to preview/edit, then pass --confirm to transmit")
        validate_channel(args.channel)
        validate_destination(args.to)
        transport = MeshtasticTransport.serial(args.device, channel=args.channel)
        try:
            _print(asdict(send_message(message, transport, args.to, confirmed=True)))
        finally:
            transport.close()
        return 0
    if args.command == "decode":
        _print(asdict(Message.decode(args.payload, default_language=args.language)))
        return 0
    if args.command == "receive":
        if not 0 <= args.timeout <= 300:
            raise ValueError("Receive timeout must be 0..300 seconds")
        transport = MeshtasticTransport.serial(args.device, channel=args.channel)
        try:
            incoming = transport.receive(timeout=args.timeout)
            if incoming is None:
                _print({"received": None})
                return 3
            message = Message.decode(incoming.payload, default_language=args.language)
            _print(
                {
                    "received": asdict(message),
                    "sender": incoming.sender,
                    "channel": incoming.channel,
                    "packet_id": incoming.packet_id,
                }
            )
        finally:
            transport.close()
        return 0
    if args.command == "transcribe":
        from .speech import WhisperSTT

        message = WhisperSTT(args.model).transcribe(args.audio, args.language)
        _print({"draft": asdict(message), "transmitted": False})
        return 0
    if args.command == "synthesize":
        from .speech import KokoroTTS

        message = Message.decode(args.payload, default_language=args.language)
        output = KokoroTTS(args.model).synthesize(message, args.output)
        _print({"audio_file": str(output), "played": False, "transmitted": False})
        return 0
    raise ValueError("Unknown command")


if __name__ == "__main__":
    raise SystemExit(main())
