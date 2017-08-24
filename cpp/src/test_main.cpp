#include "tube_server.h"
#include "utils.h"
#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <iostream>
#include <string>



using namespace std;


void test_encode_decode() {
    int32_t data[] = {0, -1, 1, 100, -100, 1000, 10000};
    char expected[][4] = { {0, 0, 0, 0},
                           {1, 0, 0, 0},
                           {2, 0, 0, 0},
                           {-56, 1, 0, 0},
                           {-57, 1, 0, 0},
                           {-48, 15, 0, 0},
                           {-96, -100, 1, 0} };
    char encoded[][4] = { {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0} };
    uint_fast8_t num_cases = sizeof(data) / sizeof(int32_t);

    cout << "Testing varint-zz encoding:" << endl;
    for(uint_fast8_t i=0; i < num_cases; ++i) {
        uint_fast8_t len = encode_int(data[i], encoded[i]);
        bool success = true;
        for(uint_fast8_t j=0; j<len; ++j) {
            if(expected[i][j] != encoded[i][j]) {
                success = false;
                break;
            }
        }
        cout << (success ? "." : "E");
    }
    cout << endl;

    cout << "Testing varint-zz decoding:" << endl;
    for(uint_fast8_t i=0; i < num_cases; ++i) {
        uint32_t num_bytes_consumed;
        int32_t n = decode_int(encoded[i], &num_bytes_consumed);
        if(n == data[i]) {
            cout << ".";
        } else {
            cout << "Error:" << endl;
            cout << "  Expected: " << data[i] << endl;
            cout << "  Got: " << n << endl;
        }
    }
    cout << endl;

}

void run_unit_tests() {
    test_encode_decode();
}

void on_rcv(TubeServer& ts, conn_id_t conn_id, const char *data) {
    string msg(data);
    reverse(msg.begin(), msg.end());
    ts.send(conn_id, msg.c_str());
}

void on_connect(TubeServer& ts, conn_id_t conn_id) {
    cout << "on_connect. conn_id: " << conn_id << endl;
}

void on_disconnect(TubeServer& ts, conn_id_t conn_id, const char *reason) {
    cout << "conn_id " << conn_id << " disconnected. Reason: ";
    cout << reason << endl;
}

void run_server() {
    uint32_t port = 8080;
    cout << "Starting server on " << port << "." << endl;
    TubeServer ts("key", "cert", port, on_rcv, on_connect, on_disconnect);
    ts.serve();
}

int main (int argc, char *argv[]) {
    run_unit_tests();
    run_server();
}
