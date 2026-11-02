# Laboratorio 100 % local — Reactive Ticketing Platform

**Estado:** aplicación validada en Docker y GitHub Actions; infraestructura AWS definida mediante Terraform, **no desplegada**. No ejecutar `terraform apply` ni `deployment/push-ecr.sh` sin presupuesto y autorización separados.

## Servicios y límites

- `ticketing-api` (localhost:8080): endpoints WebFlux.
- `ticketing-worker` (localhost:8081): consumidores SQS simulados y scheduler.
- `dynamodb-local` (localhost:8000): tablas y datos locales.
- `localstack` (localhost:4566): simulador de colas SQS, o `elasticmq` como alternativa.
- `aws-init`: inicializa tablas y colas faltantes de forma idempotente.
- **AWS real no es necesario** para ejecutar el laboratorio. Terraform `init`, `validate` y `plan` pertenecen al trabajo de infraestructura, no son requisitos para correr Docker.

## Protección de los registros históricos

El archivo `docker-compose.yml` utiliza por defecto un volumen externo:
`DYNAMODB_VOLUME_EXTERNAL=true` y `DYNAMODB_VOLUME_NAME=ticketing-ddb-restore-check`.
Verifica esa configuración en `.env` **antes de iniciar**. Si el volumen contiene registros históricos, no lo borres ni lo reemplaces.

Comandos seguros de inspección (no modifican volúmenes):

```bash
docker volume inspect ticketing-ddb-restore-check
docker compose config --services
docker compose ps
```

**Nunca ejecutes** `docker compose down -v`, `docker volume prune`, `docker system prune --volumes` ni `docker volume rm ticketing-ddb-restore-check` en este laboratorio.

## Iniciar y verificar

Desde la raíz del repositorio, configura `.env` según `.env.example`: `TICKETING_SECURITY_LOCAL_JWT_SECRET` debe ser una clave aleatoria de 32 caracteres o más. Para SQS con LocalStack usa `COMPOSE_PROFILES=localstack`; si tu instalación de LocalStack pide autenticación, sigue la opción ElasticMQ descrita en el README.

```bash
docker compose config --services
docker compose up -d --build --wait ticketing-api ticketing-worker
docker compose ps
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8081/actuator/health
```

Antes de arrancar, asegúrate de que el nombre del volumen en `.env` corresponde al histórico que deseas conservar. `aws-init` es idempotente, pero puede crear tablas o colas ausentes; no es un comando de solo lectura.

Para detener sin perder los datos:

```bash
docker compose stop
```

Para comprobar el código y CI:

```bash
mvn -B -ntp verify
```

Las pruebas automatizadas del workflow `.github/workflows/ci.yml` usan un volumen de DynamoDB específico del runner, distinto del histórico local.

## Portafolio y alcance

La plataforma demuestra Java 25, Spring Boot 4, WebFlux, DDD/Clean Architecture, DynamoDB Local, SQS simulado, idempotencia, concurrencia, pruebas, CI/CD, contenedores e infraestructura AWS como código.

Descripción precisa: **«Implementada y validada localmente; despliegue AWS diseñado y planificado con Terraform, pospuesto por costos»**.

El bucket S3 del estado remoto que se creó anteriormente en AWS es un recurso **real** y puede generar cargos pequeños. Conservarlo o eliminarlo requiere una decisión separada; no se gestiona mediante Docker Compose.

## Antes de un despliegue AWS futuro

Revisar presupuesto y alarmas de costos, permisos de acceso, valores Cognito, certificado TLS, imagen ARM64 en ECR, plan Terraform actualizado y aprobación explícita de `terraform apply`.
