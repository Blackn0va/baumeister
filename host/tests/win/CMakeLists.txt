# Das übrige Modul ist unverändert aus kraken übernommen und dort getestet (kraken/tests/win); hier nur, was neu ist.
swbh_add_test(swbh_test_win SOURCES test_crash_guard.cpp test_client_cert.cpp test_cancel_token.cpp LIBS swbh::win crypt32 ncrypt)
