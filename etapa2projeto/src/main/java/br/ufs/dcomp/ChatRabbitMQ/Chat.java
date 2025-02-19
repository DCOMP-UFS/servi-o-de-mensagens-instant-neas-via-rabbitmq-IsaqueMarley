package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Mensagem;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Conteudo;
import com.google.protobuf.ByteString;

public class Chat {
    private static final String HOST = "54.197.88.25";
    private static final String USUARIO = "admin";
    private static final String SENHA = "password";
    private static final String VIRTUAL_HOST = "/";
    
    private static String currentTarget = null;
    private static boolean isGroup = false;
    private static String prompt = ">> ";
    private static String nomeUsuario;
    private static Connection connection;
    private static Channel channel;

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        System.out.print("user: ");
        nomeUsuario = scanner.nextLine().trim();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setUsername(USUARIO);
        factory.setPassword(SENHA);
        factory.setVirtualHost(VIRTUAL_HOST);
        
        connection = factory.newConnection();
        channel = connection.createChannel();
        
        channel.queueDeclare(nomeUsuario, false, false, false, null);
        System.out.println("Fila criada para: " + nomeUsuario);

        new Thread(() -> receiveMessages()).start();
        
        while(true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            
            if(input.isEmpty()) continue;
            
            if(input.startsWith("@")) handleUser(input);
            else if(input.startsWith("#")) handleGroup(input);
            else if(input.startsWith("!")) handleCommand(input);
            else sendMessage(input);
        }
    }

    private static void handleUser(String input) {
        currentTarget = input.substring(1).trim();
        try {
            // Usa canal temporário para verificação
            Channel tempChannel = connection.createChannel();
            try {
                tempChannel.queueDeclarePassive(currentTarget);
                isGroup = false;
                prompt = "@" + currentTarget + ">> ";
            } finally {
                if (tempChannel.isOpen()) tempChannel.close();
            }
        } catch (Exception e) {
            System.out.println("Usuário '" + currentTarget + "' não encontrado!");
            currentTarget = null;
        }
    }

    private static void handleGroup(String input) {
        currentTarget = input.substring(1).trim();
        try {
            // Usa canal temporário para verificação
            Channel tempChannel = connection.createChannel();
            try {
                tempChannel.exchangeDeclarePassive(currentTarget);
                isGroup = true;
                prompt = "#" + currentTarget + ">> ";
            } finally {
                if (tempChannel.isOpen()) tempChannel.close();
            }
        } catch (Exception e) {
            System.out.println("Grupo '" + currentTarget + "' não existe! Use !addGroup para criá-lo.");
            currentTarget = null;
        }
    }

    private static void handleCommand(String input) {
        String[] cmd = input.split(" ", 3);
        try {
            switch(cmd[0]) {
                case "!addGroup":
                    channel.exchangeDeclare(cmd[1], "fanout");
                    channel.queueBind(nomeUsuario, cmd[1], "");
                    System.out.println("Grupo '" + cmd[1] + "' criado!");
                    break;
                    
                case "!addUser":
                    try (Channel tempChannel = connection.createChannel()) {
                        tempChannel.exchangeDeclarePassive(cmd[2]);
                        channel.queueBind(cmd[1], cmd[2], "");
                        System.out.println("Usuário '" + cmd[1] + "' adicionado ao grupo!");
                    }
                    break;
                    
                case "!delFromGroup":
                    channel.queueUnbind(cmd[1], cmd[2], "");
                    System.out.println("Usuário '" + cmd[1] + "' removido do grupo!");
                    break;
                    
                case "!removeGroup":
                    channel.exchangeDelete(cmd[1]);
                    System.out.println("Grupo '" + cmd[1] + "' removido!");
                    break;
                    
                default: System.out.println("Comando inválido!");
            }
        } catch(Exception e) {
            System.out.println("Erro: " + e.getMessage());
        }
    }

    private static void sendMessage(String texto) {
    // Verifica se um destinatário foi selecionado
    if (currentTarget == null) {
        System.out.println("Selecione um destinatário primeiro!");
        return;
    }
    
    try {
        // Constrói a mensagem
        Mensagem.Builder builder = Mensagem.newBuilder()
            .setEmissor(nomeUsuario)  // Define o emissor
            .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))  // Define a data
            .setHora(new SimpleDateFormat("HH:mm").format(new Date()))  // Define a hora
            .setConteudo(Conteudo.newBuilder()
                .setTipo("text/plain")  // Define o tipo de conteúdo
                .setCorpo(ByteString.copyFromUtf8(texto)));  // Define o corpo da mensagem

        // Adiciona o grupo apenas se a mensagem for para um grupo
        if (isGroup) {
            builder.setGrupo(currentTarget);  // Define o grupo
        } else {
            builder.setGrupo("");  // Define o grupo como vazio para mensagens diretas
        }

        // Constrói a mensagem final
        Mensagem mensagem = builder.build();

        // Publica a mensagem no RabbitMQ
        if (isGroup) {
            // Envia para um grupo (exchange)
            channel.basicPublish(currentTarget, "", null, mensagem.toByteArray());
        } else {
            // Envia para um usuário (fila)
            channel.basicPublish("", currentTarget, null, mensagem.toByteArray());
        }
    } catch (Exception e) {
        System.out.println("Erro ao enviar: " + e.getMessage());
    }
}

   private static void receiveMessages() {
    try {
        Consumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, 
                    AMQP.BasicProperties props, byte[] body) throws IOException {
                
                // Desserializa a mensagem
                Mensagem msg = Mensagem.parseFrom(body);

                // Ignora mensagens enviadas pelo próprio usuário
                if (msg.getEmissor().equals(nomeUsuario)) return;
                
                // Verifica se a mensagem foi enviada para um grupo
                String grupo = msg.getGrupo().isEmpty() ? "" : "#" + msg.getGrupo();

                // Formata e exibe a mensagem
                System.out.printf("%n(%s às %s) %s%s diz: %s%n",
                    msg.getData(),
                    msg.getHora(),
                    msg.getEmissor(),
                    grupo,  // Exibe o grupo apenas se a mensagem for para um grupo
                    msg.getConteudo().getCorpo().toStringUtf8());
                
                // Exibe o prompt novamente
                System.out.print(prompt);
            }
        };

        // Inicia o consumo de mensagens da fila do usuário
        channel.basicConsume(nomeUsuario, true, consumer);
    } catch (Exception e) {
        e.printStackTrace();
    }
}
}