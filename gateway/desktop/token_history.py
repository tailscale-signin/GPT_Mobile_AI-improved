"""Measured daily output plot shared with the native gateway dashboard."""
import tkinter as tk
from desktop.theme import PANEL as SURFACE, MUTED, TEXT, CYAN as ACCENT


class TokenHistory(tk.Canvas):
    def __init__(self, parent, **kwargs):
        super().__init__(parent, bg=SURFACE, highlightthickness=0, **kwargs)
        self.days = []
        self.bind('<Configure>', lambda _: self.draw())

    def update_days(self, days):
        self.days = days
        self.draw()

    def draw(self):
        c = self
        c.delete('all')
        width, height = max(c.winfo_width(), 300), max(c.winfo_height(), 100)
        if not self.days:
            c.create_text(width / 2, height / 2, text='Your token history will appear here', fill=MUTED, font=('Segoe UI', 12))
            return
        left, right, top, bottom = 68, width - 28, 20, height - 40
        values = [day.get('outputTokens') for day in self.days]
        maximum = max([value for value in values if isinstance(value, int)] + [1])
        for fraction in (0, .5, 1):
            y = bottom - (bottom - top) * fraction
            c.create_line(left, y, right, y, fill='#303C3F')
            c.create_text(left - 12, y, text=f'{round(maximum * fraction):,}', anchor='e', fill=MUTED, font=('Segoe UI', 9))
        step = (right - left) / max(len(values) - 1, 1)
        points = []
        for index, (day, value) in enumerate(zip(self.days, values)):
            x = left + index * step
            c.create_text(x, bottom + 23, text=day['day'][5:], fill=MUTED, font=('Segoe UI', 9))
            if value is None:
                c.create_text(x, bottom - 16, text='N/A', fill=MUTED, font=('Segoe UI', 9))
                points = []
                continue
            y = bottom - (bottom - top) * value / maximum
            if points:
                c.create_polygon(points[-2], bottom, points[-2], points[-1], x, y, x, bottom, fill='#25434A', outline='')
                c.create_line(points[-2], points[-1], x, y, fill=ACCENT, width=3)
            c.create_oval(x - 4, y - 4, x + 4, y + 4, fill=ACCENT, outline=SURFACE, width=2)
            if value:
                c.create_text(x, max(9, y - 14), text=f'{value:,}', fill=TEXT, font=('Segoe UI', 9))
            points.extend((x, y))
