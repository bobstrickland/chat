resource "aws_security_group" "msk" {
  name_prefix = "${var.name_prefix}-msk-"
  vpc_id      = var.vpc_id

  ingress {
    from_port   = 9098 # IAM auth port
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = ["10.20.0.0/16"]
  }

  ingress {
    from_port   = 2181
    to_port     = 2181
    protocol    = "tcp"
    cidr_blocks = ["10.20.0.0/16"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-msk-sg" })
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-msk"
  kafka_version           = var.kafka_version
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk.id]
    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  tags = var.tags
}

# ---- Topics via Mongey/kafka provider (configured in environments/dev/providers.tf) ----
# NOTE: requires network reachability to the MSK bootstrap brokers at apply time
# (CI CodeBuild project is VPC-attached — see modules/ci_cd)

resource "kafka_topic" "message_sent" {
  name               = "message.sent"
  replication_factor = var.number_of_broker_nodes >= 3 ? 3 : var.number_of_broker_nodes
  partitions          = 6
  config = {
    "retention.ms" = "604800000" # 7 days
  }
}

resource "kafka_topic" "connection_state_changed" {
  name               = "connection.state.changed"
  replication_factor = var.number_of_broker_nodes >= 3 ? 3 : var.number_of_broker_nodes
  partitions          = 6
  config = {
    "retention.ms" = "86400000" # 1 day — ephemeral state
  }
}

resource "kafka_topic" "notification_trigger" {
  name               = "notification.trigger"
  replication_factor = var.number_of_broker_nodes >= 3 ? 3 : var.number_of_broker_nodes
  partitions          = 3
  config = {
    "retention.ms" = "259200000" # 3 days
  }
}

resource "kafka_topic" "search_index" {
  name               = "search.index"
  replication_factor = var.number_of_broker_nodes >= 3 ? 3 : var.number_of_broker_nodes
  partitions          = 3
  config = {
    "retention.ms" = "604800000" # 7 days
  }
}
