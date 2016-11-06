package ru.babobka.nodemasterserver.thread;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.util.logging.Level;
import ru.babobka.nodemasterserver.exception.DistributionException;
import ru.babobka.nodemasterserver.exception.EmptyClusterException;
import ru.babobka.nodemasterserver.model.AuthResult;
import ru.babobka.nodemasterserver.server.ServerContext;
import ru.babobka.nodemasterserver.service.AuthService;
import ru.babobka.nodemasterserver.service.AuthServiceImpl;
import ru.babobka.nodemasterserver.service.NodeUsersService;
import ru.babobka.nodemasterserver.service.NodeUsersServiceImpl;
import ru.babobka.nodemasterserver.util.DistributionUtil;
import ru.babobka.nodemasterserver.util.StreamUtil;
import ru.babobka.nodeserials.NodeRequest;
import ru.babobka.nodeserials.NodeResponse;
import ru.babobka.nodeserials.RSA;

/**
 * Created by dolgopolov.a on 27.07.15.
 */
public class SlaveThread extends Thread implements Comparable<SlaveThread> {

	private final RSA rsa;

	private volatile Set<String> taskSet;

	private static final int RSA_KEY_BIT_LENGTH = 256;

	private final NodeUsersService userService = NodeUsersServiceImpl.getInstance();

	private final AuthService authService = AuthServiceImpl.getInstance();

	private final Map<Long, NodeRequest> requestMap = new ConcurrentHashMap<>();

	private volatile String login;

	private final Socket socket;

	public SlaveThread(Socket socket) {
		if (socket != null) {
			this.setName("Client Thread " + new Date());
			ServerContext.getInstance().getLogger().log("New connection " + socket);
			this.socket = socket;
			this.rsa = new RSA(RSA_KEY_BIT_LENGTH);
		} else {
			throw new IllegalArgumentException("Socket can not be null");
		}
	}

	public Set<String> getTaskSet() {
		return taskSet;
	}

	public String getLogin() {
		return login;
	}

	public Socket getSocket() {
		return socket;
	}

	public Map<Long, NodeRequest> getRequestMap() {
		return requestMap;
	}

	public synchronized void sendRequest(NodeRequest request) throws IOException {
		ServerContext.getInstance().getLogger().log("sendRequest " + request);
		if (!(request.isRaceStyle() && requestMap.containsKey(request.getTaskId()))) {
			requestMap.put(request.getRequestId(), request);
			StreamUtil.sendObject(request, socket);
			ServerContext.getInstance().getLogger().log(request + " was sent");
			userService.incrementTaskCount(login);
		} else {
			ServerContext.getInstance().getLogger().log("Request  " + request + " was ignored due to race style");
			ServerContext.getInstance().getResponseStorage().get(request.getTaskId())
					.add(NodeResponse.dummyResponse(request.getTaskId()));
		}
	}

	private synchronized void setBadAndCancelAllTheRequests() {
		if (!requestMap.isEmpty()) {
			NodeRequest request;
			for (Map.Entry<Long, NodeRequest> requestEntry : requestMap.entrySet()) {
				request = requestEntry.getValue();
				ServerContext.getInstance().getResponseStorage().addBadResponse(request.getTaskId());
				try {
					DistributionUtil.broadcastStopRequests(
							ServerContext.getInstance().getSlaves().getListByTaskId(request.getTaskId()),
							new NodeRequest(request.getTaskId(), true, request.getTaskName()));
				} catch (EmptyClusterException e) {
					ServerContext.getInstance().getLogger().log(e);
				}
			}
			requestMap.clear();
		}
	}

	private synchronized void setBadAllTheRequests() {
		if (!requestMap.isEmpty()) {
			NodeRequest request;
			for (Map.Entry<Long, NodeRequest> requestEntry : requestMap.entrySet()) {
				request = requestEntry.getValue();
				ServerContext.getInstance().getResponseStorage().addBadResponse(request.getTaskId());
			}
			ServerContext.getInstance().getLogger().log("Responses are clear");
			requestMap.clear();
		}
	}

	public void sendHeartBeating() throws IOException {

		StreamUtil.sendObject(NodeRequest.heartBeatRequest(), socket);

	}

	public synchronized void sendStopRequest(NodeRequest stopRequest) throws IOException {
		try {
			StreamUtil.sendObject(stopRequest, socket);
		} finally {

			for (Map.Entry<Long, NodeRequest> requestEntry : requestMap.entrySet()) {
				if (requestEntry.getValue().getTaskId() == stopRequest.getTaskId()) {
					ServerContext.getInstance().getResponseStorage().addStopResponse(stopRequest.getTaskId());
					requestMap.remove(requestEntry.getValue().getRequestId());
				}
			}

		}
	}

	public int getRequestCount() {
		return requestMap.size();
	}

	@Override
	public void run() {
		try {
			socket.setSoTimeout(ServerContext.getInstance().getConfig().getAuthTimeOutMillis());
			AuthResult authResult = authService.getAuthResult(rsa, socket);
			if (authResult.isValid()) {
				this.login = authResult.getLogin();
				if (!ServerContext.getInstance().getSlaves().add(this)) {
					throw new IllegalStateException("Cluster is full");
				}	
				this.taskSet = authResult.getTaskSet();
				ServerContext.getInstance().getLogger().log(login + " from " + socket + " was logged");
				while (!Thread.currentThread().isInterrupted()) {
					socket.setSoTimeout(ServerContext.getInstance().getConfig().getRequestTimeOutMillis());
					NodeResponse response = (NodeResponse) StreamUtil.receiveObject(socket);
					if (!response.isHeartBeatingResponse()) {
						ServerContext.getInstance().getLogger().log("Got response " + response);
						requestMap.remove(response.getResponseId());
						ServerContext.getInstance().getLogger().log("Remove response " + response.getResponseId());
						if (ServerContext.getInstance().getResponseStorage().exists(response.getTaskId())) {
							ServerContext.getInstance().getResponseStorage().get(response.getTaskId()).add(response);
						}
					}
				}
			} else {
				ServerContext.getInstance().getLogger().log(socket + " auth fail");
			}

		} catch (IOException e) {
			if (!(e instanceof EOFException) && (!Thread.currentThread().isInterrupted() || !socket.isClosed())) {
				ServerContext.getInstance().getLogger().log(e);
			}
			ServerContext.getInstance().getLogger().log(Level.WARNING, "Connection is closed " + socket);
		} catch (Exception e) {
			ServerContext.getInstance().getLogger().log(e);
		} finally {
			ServerContext.getInstance().getLogger().log("Removing connection " + socket);
			ServerContext.getInstance().getSlaves().remove(this);
			synchronized (SlaveThread.class) {
				if (!requestMap.isEmpty()) {
					ServerContext.getInstance().getLogger().log("Slave has a requests to redistribute");
					try {
						DistributionUtil.redistribute(this);
					} catch (DistributionException e) {
						ServerContext.getInstance().getLogger().log(e);
						setBadAndCancelAllTheRequests();
					} catch (EmptyClusterException e) {
						ServerContext.getInstance().getLogger().log(e);
						setBadAllTheRequests();
					}
				}
			}
			if (socket != null && !socket.isClosed()) {
				try {
					socket.close();
				} catch (IOException e) {
					ServerContext.getInstance().getLogger().log(e);
				}
			}
			ServerContext.getInstance().getLogger().log("User " + login + " was disconnected");

		}
	}

	public Map<String, LinkedList<NodeRequest>> getRequestsGroupedByTask() {
		Map<String, LinkedList<NodeRequest>> requestsByTaskName = new HashMap<>();
		for (Map.Entry<Long, NodeRequest> requestEntry : this.getRequestMap().entrySet()) {
			if (requestsByTaskName.containsKey(requestEntry.getValue().getTaskName())) {
				requestsByTaskName.get(requestEntry.getValue().getTaskName()).add(requestEntry.getValue());
			} else {
				requestsByTaskName.put(requestEntry.getValue().getTaskName(), new LinkedList<NodeRequest>());
				requestsByTaskName.get(requestEntry.getValue().getTaskName()).add(requestEntry.getValue());
			}
		}
		return requestsByTaskName;
	}

	@Override
	public String toString() {
		return "requests " + getRequestCount();
	}

	@Override
	public int compareTo(SlaveThread o) {
		if (o.getRequestCount() > this.getRequestCount()) {
			return -1;
		} else if (o.getRequestCount() < this.getRequestCount()) {
			return 1;
		}
		return 0;
	}

	@Override
	public void interrupt() {
		super.interrupt();
		try {
			socket.close();
		} catch (IOException e) {
			ServerContext.getInstance().getLogger().log(e);
		}

	}

}