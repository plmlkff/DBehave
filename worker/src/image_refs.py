def normalize_image_ref(image_ref: str) -> str:
    """Normalize common short Docker references for prefix allow-list checks."""
    value = image_ref.lower()
    if "/" not in value:
        return f"docker.io/library/{value}"

    first, tail = value.split("/", 1)
    if first in {"docker.io", "index.docker.io"}:
        if "/" not in tail:
            return f"docker.io/library/{tail}"
        return f"docker.io/{tail}"
    if "." not in first and ":" not in first and first != "localhost":
        return f"docker.io/{value}"
    return value
