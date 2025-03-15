package br.ufs.dcomp.ChatRabbitMQ;
import java.io.*;
import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Mensagem;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Conteudo;
import com.google.protobuf.ByteString;

import java.nio.file.*;

public class Chat {
    private static final String HOST = "3.88.219.240";
    private static final String USUARIO = "admin";
    private static final String SENHA = "password";
    private static final String VIRTUAL_HOST = "/";
    private static final String DOWNLOAD_DIR = System.getProperty("user.home") + "/chat/downloads/";

    
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
        channel.queueDeclare(nomeUsuario + "_files", false, false, false, null);
       // System.out.println("Fila criada para: " + nomeUsuario);

        new Thread(() -> receiveMessages()).start();
        new Thread(() -> receiveFiles()).start();
        
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
            System.out.println("Usuário '" + currentTarget + "' não encontrado! Abra outro terminal e crie o usuário "+ currentTarget);
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
                case "!upload":
                    if(cmd[0].equals("!upload") && cmd.length > 1) {
                        sendFile(cmd[1]);
                    } else {
                        System.out.println("Há informações demais!");
                    }
                case "!addGroup":
                    channel.exchangeDeclare(cmd[1], "fanout");
                    channel.queueBind(nomeUsuario, cmd[1], "");
                    
                    //System.out.println("Grupo '" + cmd[1] + "' criado!");
                    break;
                    
                case "!addUser":
                    try (Channel tempChannel = connection.createChannel()) {
                         channel.queueDeclare(cmd[1], false, false, false, null);
                         channel.queueDeclare(cmd[1] + "_files", false, false, false, null);
                        tempChannel.exchangeDeclarePassive(cmd[2]);
                        channel.queueBind(cmd[1], cmd[2], "");
                        
                       // System.out.println("Usuário '" + cmd[1] + "' adicionado ao grupo!");
                    }catch (IOException e) {
                        System.out.println("Fila '" + cmd[1] + "' não encontrada. Destinatário pode estar offline.");
                        return;
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
            System.out.println("Há algum erro no seu comando. Caso discorde contate o SUPORTE!");
            System.out.println("Erro: " + e.getMessage());
            System.out.println("O erro é este que foi escrito!");
        }
    }

private static void sendFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
        System.out.println("Arquivo não encontrado!");
        return;
        }
        
        if (!Files.isRegularFile(file.toPath())) {
        System.out.println("O caminho especificado não é um arquivo!");
        return;
        }

        if (currentTarget == null) {
            System.out.println("Selecione um destinatário primeiro!");
            return;
        }
        
        
        new Thread(() -> {
            try {
                System.out.println("Enviando \"" + filePath + "\" para " + (isGroup ? "#" : "@") + currentTarget);
                Path path = file.toPath();
                String mimeType = Files.probeContentType(path);
                byte[] fileBytes = Files.readAllBytes(path);

                Mensagem mensagem = Mensagem.newBuilder()
                    .setEmissor(nomeUsuario)
                    .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))
                    .setHora(new SimpleDateFormat("HH:mm").format(new Date()))
                    .setConteudo(Conteudo.newBuilder()
                        .setTipo(mimeType)
                        .setNome(file.getName())  // Nome do arquivo
                        .setCorpo(ByteString.copyFrom(fileBytes)))
                    .setGrupo(isGroup ? currentTarget : "")
                    .build();

                /*Mensagem mensagem = Mensagem.newBuilder()
                    .setEmissor(nomeUsuario)
                    .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))
                    .setHora(new SimpleDateFormat("HH:mm").format(new Date()))
                    .setConteudo(Conteudo.newBuilder()
                        .setTipo(mimeType)
                        .setCorpo(ByteString.copyFrom(fileBytes)))
                    .setGrupo(isGroup ? currentTarget : "")
                    .build();*/

                channel.basicPublish("", currentTarget + "_files", null, mensagem.toByteArray());
                System.out.println("Arquivo \"" + filePath + "\" foi enviado para " + (isGroup ? "#" : "@") + currentTarget + "!");
                
                System.out.println("...");
            } catch (Exception e) {
                System.out.println("Erro ao enviar arquivo: " + e.getMessage());
            }
        }).start();
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
private static void receiveFiles() {
    try {
        // Verifica se o diretório de downloads existe, caso contrário, tenta criá-lo
        File downloadDir = new File(DOWNLOAD_DIR);
        if (!downloadDir.exists()) {
            if (downloadDir.mkdirs()) {
                System.out.println("Diretório de downloads criado: " + DOWNLOAD_DIR);
            } else {
                System.out.println("Erro ao criar o diretório de downloads: " + DOWNLOAD_DIR);
                return;
            }
        }

        Consumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, 
                    AMQP.BasicProperties props, byte[] body) throws IOException {
                
                Mensagem msg = Mensagem.parseFrom(body);
                if (msg.getEmissor().equals(nomeUsuario)) return;

                String fileName = msg.getConteudo().getNome();
                Path filePath = Paths.get(DOWNLOAD_DIR, fileName);

                // Tenta escrever o arquivo na pasta de downloads
                try {
                    Files.write(filePath, msg.getConteudo().getCorpo().toByteArray());
                    System.out.printf("\n(%s às %s) Arquivo \"%s\" recebido de @%s!%n", 
                            msg.getData(), msg.getHora(), fileName, msg.getEmissor());
                } catch (IOException e) {
                    System.out.println("Erro ao salvar o arquivo " + fileName + ": " + e.getMessage());
                }

                // Exibe o prompt novamente
                System.out.print(prompt);
            }
        };

        // Inicia o consumo de arquivos da fila do usuário
        channel.basicConsume(nomeUsuario + "_files", true, consumer);
    } catch (Exception e) {
        e.printStackTrace();
    }
}

/*

private static void receiveFiles() {
        try {
     


            File downloadDir = new File(DOWNLOAD_DIR);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            
            Consumer consumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope, 
                        AMQP.BasicProperties props, byte[] body) throws IOException {
                    
                    Mensagem msg = Mensagem.parseFrom(body);
                    if (msg.getEmissor().equals(nomeUsuario)) return;
                    
                   String fileName = msg.getConteudo().getNome();
Path filePath = Paths.get(DOWNLOAD_DIR, fileName);

                    Files.write(filePath, msg.getConteudo().getCorpo().toByteArray());
                    
                    System.out.printf("\n(%s às %s) Arquivo \"%s\" recebido de @%s!%n", 
                        msg.getData(), msg.getHora(), fileName, msg.getEmissor());
                    System.out.print(prompt);
                }
            };
            
            channel.basicConsume(nomeUsuario + "_files", true, consumer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
*/

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