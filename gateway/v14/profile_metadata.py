"""Validated presentation headers; never authentication or execution authority."""
import base64
import re
import unicodedata


def profile_metadata(headers):
    profile_id = headers.get('x-gateway-profile-id', '')
    if profile_id and not re.fullmatch(r'[A-Za-z0-9_-]{1,160}', profile_id):
        raise ValueError('Invalid original profile identity')
    encoded = headers.get('x-gateway-profile-name-base64', '')
    name = ''
    if encoded:
        if len(encoded) > 1366 or not re.fullmatch(r'[A-Za-z0-9_-]+', encoded):
            raise ValueError('Invalid original profile name encoding')
        try:
            raw = base64.b64decode(encoded + '=' * (-len(encoded) % 4), altchars=b'-_', validate=True)
            if not 1 <= len(raw) <= 1024:
                raise ValueError('Original profile name is too large')
            name = raw.decode('utf-8')
        except (ValueError, UnicodeDecodeError) as error:
            raise ValueError('Invalid original profile name encoding') from error
        if any(unicodedata.category(character) == 'Cc' for character in name):
            raise ValueError('Original profile name contains control characters')
    return {'profile_id': profile_id or None, 'profile_name': name or None}
