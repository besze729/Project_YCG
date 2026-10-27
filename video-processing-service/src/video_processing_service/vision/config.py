"""Vision configuration and prompt snapshots, loaded once per analysis."""

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
import re
from urllib.parse import urlsplit

from ..config import _nonempty_string, _positive_int, _read_config, _section


DEFAULT_CONFIG = Path(__file__).with_name("config.toml")


@dataclass(frozen=True)
class VisionConfig:
    base_url: str
    model: str
    max_images_per_request: int
    connect_timeout_seconds: int
    request_timeout_seconds: int
    max_output_tokens: int
    prompt_template: str

    def render_prompt(self, game_name: str) -> str:
        if not game_name.strip():
            raise ValueError("Game title must not be blank")
        # One literal replacement: a title cannot introduce another template pass.
        return self.prompt_template.replace("<?GAME_TITLE>", game_name)


def prompt_hash(prompt: str) -> str:
    return sha256(prompt.encode("utf-8")).hexdigest()


def load_vision_config(path: str | Path | None = None) -> VisionConfig:
    data = _read_config(DEFAULT_CONFIG)
    prompt_root = DEFAULT_CONFIG.parent
    if path is not None:
        override_path = Path(path).resolve()
        override = _read_config(override_path)
        if set(override) != {"vision"} or not isinstance(override["vision"], dict):
            raise ValueError("Vision override must contain only a [vision] section")
        if "prompt_file" in override["vision"]:
            prompt_root = override_path.parent
        data["vision"].update(override["vision"])
    values = _section(data, "vision", {
        "base_url", "model", "max_images_per_request", "connect_timeout_seconds",
        "request_timeout_seconds", "max_output_tokens", "prompt_file",
    })
    base_url = _nonempty_string(values["base_url"], "vision.base_url").rstrip("/")
    url = urlsplit(base_url)
    if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("vision.base_url must be an HTTP(S) URL without credentials, query or fragment")
    prompt_file = prompt_root / _nonempty_string(values["prompt_file"], "vision.prompt_file")
    try:
        template = prompt_file.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise ValueError(f"Cannot read vision prompt: {prompt_file}") from exc
    if "<?GAME_TITLE>" not in template or set(re.findall(r"<\?([^>]+)>", template)) != {"GAME_TITLE"}:
        raise ValueError("Vision prompt must contain <?GAME_TITLE> and no unknown template markers")
    return VisionConfig(
        base_url=base_url,
        model=_nonempty_string(values["model"], "vision.model"),
        max_images_per_request=_positive_int(values["max_images_per_request"], "vision.max_images_per_request"),
        connect_timeout_seconds=_positive_int(values["connect_timeout_seconds"], "vision.connect_timeout_seconds"),
        request_timeout_seconds=_positive_int(values["request_timeout_seconds"], "vision.request_timeout_seconds"),
        max_output_tokens=_positive_int(values["max_output_tokens"], "vision.max_output_tokens"),
        prompt_template=template,
    )
