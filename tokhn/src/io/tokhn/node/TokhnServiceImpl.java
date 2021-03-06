/*
 * Copyright 2018 Matt Liotta
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.tokhn.node;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.grpc.stub.StreamObserver;
import io.tokhn.core.Address;
import io.tokhn.core.Block;
import io.tokhn.core.Blockchain;
import io.tokhn.core.LocalBlock;
import io.tokhn.core.Transaction;
import io.tokhn.core.UTXO;
import io.tokhn.core.Wallet;
import io.tokhn.grpc.BlockModel;
import io.tokhn.grpc.BlockRequest;
import io.tokhn.grpc.BlockResponse;
import io.tokhn.grpc.NetworkModel;
import io.tokhn.grpc.PartialChainRequest;
import io.tokhn.grpc.PartialChainResponse;
import io.tokhn.grpc.TokhnServiceGrpc.TokhnServiceImplBase;
import io.tokhn.grpc.TransactionModel;
import io.tokhn.grpc.UtxoRequest;
import io.tokhn.grpc.UtxoResponse;
import io.tokhn.grpc.WelcomeModel;
import io.tokhn.grpc.WelcomeRequest;
import io.tokhn.grpc.WelcomeRequest.PeerType;
import io.tokhn.store.MapDBWalletStore;
import io.tokhn.grpc.WelcomeResponse;
import io.tokhn.util.GRPC;
import io.tokhn.util.Hash;

public class TokhnServiceImpl extends TokhnServiceImplBase {
	private static final List<StreamObserver<TransactionModel>> txObservers = new ArrayList<>();
	private static final List<StreamObserver<BlockModel>> blockObservers = new ArrayList<>();
	private final Map<Network, Blockchain> chains;
	private Wallet wallet = null;
	
	public TokhnServiceImpl(Map<Network, Blockchain> chains) {
		this.chains = chains;
		try {
			wallet = new Wallet(new MapDBWalletStore());
			if(wallet.getPrivateKey() == null || wallet.getPublicKey() == null) {
				System.out.println("No wallet found; internal mining rewards will go to charity. Generate a wallet to keep your own rewards.");
			}
		} catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeySpecException e) {
			System.err.println(e);
			System.exit(-1);
		}
	}
	
	public void getWelcome(WelcomeRequest request, StreamObserver<WelcomeResponse> responseObserver) {
		PeerType peerType = request.getPeerType();
		System.out.printf("A %s just joined.\n", peerType);
		
		responseObserver.onNext(WelcomeResponse.newBuilder()
				.addAllWelcomes(chains.values().stream().map(chain -> {
					return WelcomeModel.newBuilder()
							.setNetwork(NetworkModel.valueOf(chain.getNetwork().name()))
							.setTimestamp(Instant.now().getEpochSecond())
							.setDifficulty(chain.getDifficulty())
							.setReward(chain.getReward())
							.setLatestBlock(GRPC.transform(chain.getLatestBlock()))
							.build();
				}).collect(Collectors.toList()))
				.build());
		responseObserver.onCompleted();
	}

	public void getBlock(BlockRequest request, StreamObserver<BlockResponse> responseObserver) {
		Network network = Network.valueOf(request.getNetwork().name());
		Blockchain chain = chains.get(network);
		responseObserver.onNext(BlockResponse.newBuilder()
				.setNetwork(NetworkModel.valueOf(chain.getNetwork().name()))
				.setBlock(GRPC.transform(chain.getBlock(new Hash(request.getHash()))))
				.build());
		responseObserver.onCompleted();
	}

	public void getPartialChain(PartialChainRequest request, StreamObserver<PartialChainResponse> responseObserver) {
		Network network = Network.valueOf(request.getNetwork().name());
		Blockchain chain = chains.get(network);
		if(chain.getLength() >= request.getEndIndex()) {
			//we have the requested blocks
			LinkedList<Block> blocks = new LinkedList<>();
			LocalBlock b = chain.getLatestBlock();
			while(b != null) {
				if(b.getIndex() >= request.getStartIndex() && b.getIndex() <= request.getEndIndex()) {
					blocks.addFirst(b);
				}
				b = chain.getBlock(b.getPreviousHash());
			}
			responseObserver.onNext(PartialChainResponse.newBuilder()
					.setNetwork(NetworkModel.valueOf(chain.getNetwork().name()))
					.addAllBlocks(blocks.stream().map(block -> GRPC.transform(block)).collect(Collectors.toList()))
					.build());
			responseObserver.onCompleted();
		} else {
			responseObserver.onError(new Exception("Requested chain is too long"));
		}
	}

	public void getUtxos(UtxoRequest request, StreamObserver<UtxoResponse> responseObserver) {
		try {
			Network network = Network.valueOf(request.getNetwork().name());
			Blockchain chain = chains.get(network);
			List<UTXO> utxos = chain.getUtxosForAddress(new Address(request.getAddress().toByteArray()));
			responseObserver.onNext(UtxoResponse.newBuilder()
					.setNetwork(NetworkModel.valueOf(chain.getNetwork().name()))
					.addAllUtxos(utxos.stream().map(utxo -> GRPC.transform(utxo)).collect(Collectors.toList()))
					.build());
			responseObserver.onCompleted();
		} catch (InvalidNetworkException e) {
			System.err.println(e);
		}
	}

	public StreamObserver<TransactionModel> streamTransactions(StreamObserver<TransactionModel> responseObserver) {
		//add it to our list of observers
		txObservers.add(responseObserver);
		
		return new StreamObserver<TransactionModel>() {
			@Override
	        public void onNext(TransactionModel transactionModel) {
				for(StreamObserver<TransactionModel> observer : txObservers) {
					observer.onNext(transactionModel);
				}
				
				Network network = Network.valueOf(transactionModel.getNetwork().name());
				Blockchain chain = chains.get(network);
				if(network.getParams().getMaxInternalMineDifficulty() >= chain.getDifficulty()) {
					Block latestBlock = chain.getLatestBlock();
					List<Transaction> transactions = new LinkedList<>();
					Address address = network.getCharityAddress();
					if(wallet != null) {
						address = wallet.getAddress(network);
					}
					transactions.add(Transaction.rewardOf(address, chain.getReward()));
					transactions.add(GRPC.transform(transactionModel));
					chain.addBlockToChain(Block.findBlock(network, latestBlock.getIndex() + 1, latestBlock.getHash(), transactions, chain.getDifficulty()));
				}
			}
			
			@Override
	        public void onError(Throwable t) {
	          System.err.println(t);
	        }
			
			@Override
	        public void onCompleted() {
				//guess the client doesn't want to exchange transactions anymore
				responseObserver.onCompleted();
				//remove it from our list of observers
				txObservers.remove(responseObserver);
			}
		};
	}

	public StreamObserver<BlockModel> streamBlocks(StreamObserver<BlockModel> responseObserver) {
		//add it to our list of observers
		blockObservers.add(responseObserver);
		
		return new StreamObserver<BlockModel>() {
			@Override
	        public void onNext(BlockModel blockModel) {
				Network network = Network.valueOf(blockModel.getNetwork().name());
				Blockchain chain = chains.get(network);
				if(chain.getBlock(new Hash(blockModel.getHash())) != null) {
					//we already have it so don't bother
				} else if (chain.addBlockToChain(GRPC.transform(blockModel))) {
					// broadcast new block to peers
					for(StreamObserver<BlockModel> observer : blockObservers) {
						observer.onNext(blockModel);
					}
				}
			}
			
			@Override
	        public void onError(Throwable t) {
	          System.err.println(t);
	        }
			
			@Override
	        public void onCompleted() {
				//guess the client doesn't want to exchange blocks anymore
				responseObserver.onCompleted();
				//remove it from our list of observers
				blockObservers.remove(responseObserver);
			}
		};
	}
}
