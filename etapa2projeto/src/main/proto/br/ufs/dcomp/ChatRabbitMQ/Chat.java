package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;


public class Chat {
    private static final String HOST = "52.207.216.123";
    private static final String USUARIO = "admin";
    private static final String SENHA = "password";
    private static final String VIRTUAL_HOST = "/";
    
    private static final Map<String, String> grupos = new HashMap<>();
    
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        System.out.print("user: ");
        String nomeUsuario = scanner.nextLine().trim();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setUsername(USUARIO);
        factory.setPassword(SENHA);
        factory.setVirtualHost(VIRTUAL_HOST);
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        
        // Criar fila do usuário
        channel.queueDeclare(nomeUsuario, false, false, false, null);
        System.out.println("Fila criada para: " + nomeUsuario);
        
        // Inicia thread para receber mensagens
        new Thread(() -> {
            try {
                Consumer consumer = new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                        try {
                            MensagemProto.Mensagem msg = MensagemProto.Mensagem.parseFrom(body);
                            String timestamp = msg.getData() + " às " + msg.getHora();
                            System.out.println("(" + timestamp + ") " + msg.getEmissor() + (msg.hasGrupo() ? "#" + msg.getGrupo() : "") + " diz: " + msg.getConteudo().getCorpo().toStringUtf8());
                        } catch (InvalidProtocolBufferException e) {
                            e.printStackTrace();
                        }
                    }
                };
                channel.basicConsume(nomeUsuario, true, consumer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        
        String destinatario = null;
        String prompt = ">> ";
        
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            
            if (input.startsWith("@")) {
                destinatario = input.substring(1).trim();
                channel.queueDeclare(destinatario, false, false, false, null);
                prompt = "@" + destinatario + ">> ";
            } else if (input.startsWith("#")) {
                destinatario = input.substring(1).trim();
                if (!grupos.containsKey(destinatario)) {
                    System.out.println("Grupo não existe.");
                    continue;
                }
                prompt = "#" + destinatario + ">> ";
            } else if (input.startsWith("!")) {
                String[] comando = input.split(" ", 3);
                switch (comando[0]) {
                    case "!addGroup":
                        grupos.put(comando[1], nomeUsuario);
                        channel.exchangeDeclare(comando[1], "fanout");
                        channel.queueBind(nomeUsuario, comando[1], "");
                        break;
                    case "!addUser":
                        if (grupos.containsKey(comando[2])) {
                            channel.queueBind(comando[1], comando[2], "");
                        } else {
                            System.out.println("Grupo não encontrado.");
                        }
                        break;
                    case "!delFromGroup":
                        channel.queueUnbind(comando[1], comando[2], "");
                        break;
                    case "!removeGroup":
                        if (grupos.containsKey(comando[1])) {
                            channel.exchangeDelete(comando[1]);
                            grupos.remove(comando[1]);
                        } else {
                            System.out.println("Grupo não encontrado.");
                        }
                        break;
                }
            } else if (destinatario != null) {
                MensagemProto.Mensagem mensagem = MensagemProto.Mensagem.newBuilder()
                        .setEmissor(nomeUsuario)
                        .setData(new SimpleDateFormat("dd/MM/yyyy").format(new Date()))
                        .setHora(new SimpleDateFormat("HH:mm").format(new Date()))
                        .setConteudo(MensagemProto.Conteudo.newBuilder()
                                .setTipo("text/plain")
                                .setCorpo(com.google.protobuf.ByteString.copyFromUtf8(input)))
                        .build();
                
                if (grupos.containsKey(destinatario)) {
                    channel.basicPublish(destinatario, "", null, mensagem.toByteArray());
                } else {
                    channel.basicPublish("", destinatario, null, mensagem.toByteArray());
                }
            } else {
                System.out.println("Escolha um destinatário com @nome ou #grupo");
            }
        }
    }
}
