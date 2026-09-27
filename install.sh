#!/bin/sh
set -eu

# Uso: sh install.sh IP_LAN_DO_SERVIDOR (somente na primeira instalação).
cd "$(dirname "$0")"
command -v docker >/dev/null || { echo 'Docker não encontrado.' >&2; exit 1; }
docker compose version >/dev/null || { echo 'Docker Compose v2 não encontrado.' >&2; exit 1; }

private_ip() (
  case "$1" in ''|*[!0-9.]*|.*|*.|*..*) return 1 ;; esac
  set -f
  old_ifs=$IFS
  IFS=.
  set -- $1
  IFS=$old_ifs
  [ "$#" -eq 4 ] || return 1
  for octet do
    case "$octet" in ''|0[0-9]*|*[!0-9]*) return 1 ;; esac
    [ "${#octet}" -le 3 ] && [ "$octet" -le 255 ] || return 1
  done
  case "$1:$2:$3:$4" in
    10:*|192:168:*|172:1[6-9]:*|172:2[0-9]:*|172:3[01]:*) ;;
    *) return 1 ;;
  esac
  [ "$4" -ne 0 ] && [ "$4" -ne 255 ]
)

if [ -L .env ]; then
  echo 'Arquivo .env não pode ser link simbólico.' >&2
  exit 1
fi

if [ ! -e .env ]; then
  [ "$#" -eq 1 ] && [ -n "$1" ] || { echo 'Uso: sh install.sh IP_LAN_DO_SERVIDOR' >&2; exit 1; }
  private_ip "$1" || { echo 'Informe IPv4 privado válido (RFC1918) da interface LAN.' >&2; exit 1; }
  umask 077
  printf 'NAS_IP=%s\nTV_PUBLISH_PASSWORD=123\nTV_VIEW_PASSWORD=123\n' "$1" > .env
  created=1
else
  [ -f .env ] || { echo 'Arquivo .env inválido.' >&2; exit 1; }
  chmod 600 .env
  created=0
fi

# Credenciais e IP do arquivo, não de variáveis exportadas na máquina.
unset NAS_IP TV_PUBLISH_PASSWORD TV_VIEW_PASSWORD
docker compose config --quiet
docker compose run --rm mediamtx --validate-conf=/mediamtx.yml
docker compose up -d

if [ "$created" -eq 1 ]; then
  printf '\nUsuários tvpublisher (APK) e tvviewer (navegador): senha 123.\n'
else
  printf '\nInstalação existente: .env preservado. Consulte suas credenciais nesse arquivo.\n'
fi
printf 'Abra http://%s:8889/tv/ na LAN. Nunca exponha portas na Internet.\n' "$(sed -n 's/^NAS_IP=//p' .env)"
