# Promotes the giftcards module to a service of its own in qa, with its own
# autoscaling. Same image, no build and no release: only this variable changes.
#
# It is applied in two steps, so the module is never without a deployment that
# serves it (infra/terraform/README.md, "Promover un módulo"):
#
#   1. As it is (transition = "traffic-in-nucleo"):
#        terraform apply -var-file=promocion-giftcards.tfvars
#      The service is created with the release that is deployed and starts. The
#      nucleo keeps serving the module and keeps receiving its API. Wait until
#      the new service is stable before the next step.
#
#   2. With the `transition` line removed:
#        terraform apply -var-file=promocion-giftcards.tfvars
#      The API of the module goes to the new service. Then redeploy the current
#      release (the deploy workflow, same tag): the nucleo starts without the
#      module.
#
# To bring the module back to the nucleo, three steps:
#
#   1. transition = "traffic-in-service", apply, and redeploy the current
#      release: the nucleo serves the module again.
#   2. transition = "traffic-in-nucleo", apply: the API goes back to the nucleo.
#   3. Stop passing this file, apply: the service is removed.

promoted_modules = {
  giftcards = {
    cpu    = 1024
    memory = 2048

    autoscaling = {
      min        = 2
      max        = 12
      cpu_target = 60 # average CPU, percent

      # Backlog per task: visible messages in the work queue / running tasks.
      queue = {
        name                    = "giftcards-generacion"
        backlog_per_task_target = 10 # a message is one fragment of a lote
      }

      scale_out_cooldown = 60  # add tasks quickly
      scale_in_cooldown  = 300 # remove them slowly
    }

    transition = "traffic-in-nucleo"
  }
}
