"""Logging fake player used by the CLI (mirrors PlayerBridge.kt behaviour)."""


class LoggingPlayer:
    def __init__(self):
        self.log = []

    def _rec(self, what, **kw):
        entry = {"action": what, **kw}
        self.log.append(entry)
        print(f"PLAYER: {entry}")
        return entry

    def play_song(self, query):
        return self._rec("play_song", query=query)

    def pause(self):
        return self._rec("pause")

    def resume(self):
        return self._rec("resume")

    def next(self):
        return self._rec("next")

    def previous(self):
        return self._rec("previous")

    def set_volume(self, level):
        return self._rec("set_volume", level=level)

    def set_shuffle(self, on):
        return self._rec("set_shuffle", on=on)

    def like_current(self):
        return self._rec("like_current")
