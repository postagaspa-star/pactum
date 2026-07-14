"""Auth v1: due bearer token statici (figlio, genitore) letti dall'ambiente.
Token assente o sconosciuto -> 401; token valido ma del ruolo sbagliato -> 403."""

import secrets
from typing import Annotated

from fastapi import Header, HTTPException, Request


def _estrai_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="token mancante")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="token mancante")
    return token


def _ruolo(request: Request, authorization: str | None) -> str:
    token = _estrai_token(authorization)
    settings = request.app.state.settings
    if secrets.compare_digest(token, settings.token_figlio):
        return "figlio"
    if secrets.compare_digest(token, settings.token_genitore):
        return "genitore"
    raise HTTPException(status_code=401, detail="token sconosciuto")


def richiede_figlio(request: Request, authorization: Annotated[str | None, Header()] = None) -> str:
    ruolo = _ruolo(request, authorization)
    if ruolo != "figlio":
        raise HTTPException(status_code=403, detail="serve il token del figlio")
    return ruolo


def richiede_genitore(request: Request, authorization: Annotated[str | None, Header()] = None) -> str:
    ruolo = _ruolo(request, authorization)
    if ruolo != "genitore":
        raise HTTPException(status_code=403, detail="serve il token del genitore")
    return ruolo


def richiede_patto(request: Request, authorization: Annotated[str | None, Header()] = None) -> str:
    return _ruolo(request, authorization)
