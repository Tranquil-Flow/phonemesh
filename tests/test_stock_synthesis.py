from types import SimpleNamespace

from phonemesh.message import Message
from phonemesh.speech import KokoroTTS


def test_full_budget_stock_text_can_be_synthesized(tmp_path):
    voice = tmp_path / "voices" / "ff_siwis.pt"
    voice.parent.mkdir()
    voice.write_text("fixture, not model weights")

    class Audio:
        def detach(self):
            return self

        def cpu(self):
            return self

        def tolist(self):
            return [0.2, -0.2] * 10

    class Pipeline:
        def g2p(self, text):
            return "abc", None

        def generate_from_tokens(self, phones, *, voice):
            yield SimpleNamespace(audio=Audio())

    engine = KokoroTTS(tmp_path)
    engine._pipelines["fr"] = Pipeline()
    # Received plain text consumes no tag bytes. Don't add one just to synthesize.
    message = Message.decode("a" * 200, default_language="fr")
    engine.synthesize(message, tmp_path / "stock.wav")
    assert (tmp_path / "stock.wav").is_file()
