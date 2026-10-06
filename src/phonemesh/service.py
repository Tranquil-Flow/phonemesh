"""Preview/confirmation boundary shared by all send paths."""

from .message import Message
from .transport import Receipt, Transport


def send_message(
    message: Message, transport: Transport, destination: str, *, confirmed: bool = False
) -> Receipt:
    payload = message.encode()
    if confirmed is not True:
        raise PermissionError("Preview and confirm the exact text before sending")
    return transport.send(payload, destination)
