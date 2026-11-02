# Infraestructura (Terraform)

Módulos reutilizables en `modules/` y un directorio por entorno en `envs/` (estado remoto propio, sin *workspaces*).

| Módulo | Contenido |
|---|---|
| `observability` | Llave KMS (rotación anual), tópico SNS de alarmas, *log groups* cifrados |
| `network` | VPC privada multi-AZ **sin NAT**, VPC Endpoints (Gateway: DynamoDB, S3; Interface: SQS, ECR, Logs, Secrets Manager, SSM, STS, X-Ray), *flow logs* |
| `data` | 7 tablas DynamoDB *on-demand*, GSIs `deadline-index`, `customer-index` y `order-index`, TTL de idempotencia, PITR, SSE-KMS |
| `messaging` | Colas SQS Standard + DLQ (`maxReceiveCount = 5`), *long polling*, alarmas de DLQ y antigüedad |
| `identity` | Cognito User Pool, *resource server* `ticketing` con *scopes* `purchase` y `admin`, cliente web (PKCE) y cliente de máquina |
| `edge` | API Gateway HTTP API con *authorizer* JWT y *throttling*, VPC Link, ALB interno con WAF (reglas administradas + límite por IP) |
| `compute` | ECR inmutable con escaneo, ECS Fargate (ARM64), servicios `api` y `worker`, roles IAM de mínimo privilegio, *autoscaling* por CPU y por *backlog* de SQS, Fargate Spot para el *worker* |

```bash
cd envs/dev
cp terraform.tfvars.example terraform.tfvars
terraform init -backend-config="bucket=<bucket-de-estado>"
terraform plan -var="image_tag=$(git rev-parse --short HEAD)"
```

Validación sin credenciales: `terraform init -backend=false && terraform validate`.

**Notas:**
- AWS WAF no se asocia a HTTP APIs; el *web ACL* protege el ALB que está detrás del VPC Link.
- Cognito emite los *scopes* como `ticketing/purchase`; la API los normaliza a `ticketing:purchase`.
- API Gateway corta las integraciones a los 29 s, así que el SSE se reconecta (EventSource lo hace solo). Para *streams* largos en producción se expondría por CloudFront o un ALB público con WAF.
- `staging` y `prod` replican `envs/dev` en cuentas separadas, con `deletion_protection = true`, 3 AZ y más tareas.

## Endurecimiento pendiente de habilitación (PR #2)

- **ALB TLS (opcional):** el módulo `edge` acepta `alb_tls = { certificate_arn = "...", server_name = "..." }`.
  Cuando se suministran ambos valores, API Gateway usa HTTPS validado con SNI contra el listener 443, y HTTP 80 redirige a HTTPS. Sin esta configuración **se conserva HTTP**, para evitar un cambio de tráfico sin certificado. El certificado ACM debe estar en la región del ALB y su nombre debe coincidir con `server_name`.
- **Zonas de disponibilidad:** `network.availability_zone_names` permite fijar la lista exacta y su orden. Para entornos existentes, consultar el estado Terraform y las subredes reales antes de proporcionarla; omitirla conserva el comportamiento previo. El hallazgo Checkov sobre descubrimiento dinámico puede persistir mientras esta opción no sea obligatoria.
- **ALB deletion protection:** el entorno `dev` conserva `false` intencionalmente para permitir su ciclo de vida. En producción se debe habilitar mediante `deletion_protection = true`.
- **Rutas públicas:** las dos rutas GET de disponibilidad permanecen `authorization_type = "NONE"` por contrato de API; la compra y administración continúan protegidas mediante JWT. Esta exposición es intencional y debe quedar registrada como excepción auditada de Checkov.
- **Antes de un despliegue:** ejecutar `terraform plan` con backend y credenciales del entorno, verificar cambios de red/ALB, y probar entrega real de logs WAF y ALB. No asumir que `terraform validate` equivale a un despliegue exitoso.
