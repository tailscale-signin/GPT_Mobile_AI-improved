"""GUI-independent display formatting for measured statistics."""


def number(value):
    return f'{value:,}' if type(value) is int and value >= 0 else 'Not measured'
