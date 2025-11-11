package org.radarbase.android.auth.oauth2

import org.radarbase.android.auth.sep.SEPClient
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient

class OAuth2Client(
    serverConfig: ServerConfig,
    clientId: String,
    clientSecret: String,
    client: RestClient?
) : SEPClient(serverConfig, clientId, clientSecret, client)