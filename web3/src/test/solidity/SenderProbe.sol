// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract SenderProbe {

    function currentSender() external view returns (address) {
        return msg.sender;
    }
}
