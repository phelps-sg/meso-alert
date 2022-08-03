package actors

import dao.Hook

case class HookNotRegisteredException[X](uri: X)
    extends Exception(s"No webhook registered for $uri")

case class HookNotStartedException[X](uri: X)
    extends Exception(s"No webhook started for $uri")

case class HookAlreadyRegisteredException[X](hook: Hook[X])
    extends Exception(s"Webhook already registered with same key as $hook")

case class HookAlreadyStartedException[X](uri: X)
    extends Exception(s"Webhook already started for $uri")
